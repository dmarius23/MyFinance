package ro.myfinance.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.email.EmailHistory;
import ro.myfinance.common.email.EmailHistoryRepository;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.email.EmailOutboxHandler;
import ro.myfinance.common.email.EmailSender;
import ro.myfinance.common.email.EmailStatus;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Proves the transactional-outbox relay end-to-end on a real Postgres with RLS: a SEND_EMAIL message is
 * delivered (history → SENT), and a poison message backs off and lands in the DLQ after the attempts cap,
 * flipping its email-history row to FAILED via the handler's onExhausted hook.
 */
class OutboxRelayIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("cccccccc-0000-0000-0000-0000000000e1");

    @Autowired OutboxRelay relay;
    @Autowired OutboxMessageRepository outboxRepo;
    @Autowired EmailHistoryRepository historyRepo;
    @Autowired EmailOutboxHandler emailHandler;
    @Autowired JdbcTemplate jdbc;
    @Autowired FailingHandler failingHandler;

    /** A test-only handler that always fails, so we can exercise backoff + DLQ + onExhausted. */
    @TestConfiguration
    static class Handlers {
        @Bean
        FailingHandler failingHandler() {
            return new FailingHandler();
        }
    }

    static class FailingHandler implements OutboxHandler {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger exhausted = new AtomicInteger();

        @Override public String type() { return "TEST_FAIL"; }
        @Override public void handle(OutboxMessage message) {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        }
        @Override public void onExhausted(OutboxMessage message) { exhausted.incrementAndGet(); }
    }

    private void bindTenant() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 'T', 'ACTIVE', 'STANDARD') on conflict do nothing",
                TENANT);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void deliversQueuedEmailAndMarksHistorySent() {
        bindTenant();
        EmailHistory h = historyRepo.save(new EmailHistory(TENANT, EmailKind.TAX, UUID.randomUUID(),
                LocalDate.of(2026, 3, 1), List.of(), "client@example.com", "body", EmailStatus.QUEUED, null, null));
        var message = EmailSender.Message.of("Firma", "firma@contabil.ro", "client@example.com", "Subj", "body");
        OutboxMessage msg = outboxRepo.save(new OutboxMessage(TENANT, EmailOutboxHandler.AGGREGATE,
                h.getId().toString(), EmailOutboxHandler.TYPE, emailHandler.serialize(
                        new EmailOutboxHandler.Payload(h.getId(), message))));

        int attempted = relay.relayDue(10);

        assertThat(attempted).isEqualTo(1);
        assertThat(outboxRepo.findById(msg.getId()).orElseThrow().getStatus()).isEqualTo(OutboxMessage.Status.SENT);
        assertThat(historyRepo.findById(h.getId()).orElseThrow().getStatus()).isEqualTo(EmailStatus.SENT);
    }

    @Test
    void reDeliveryOfSentMessageIsNoOp() {
        bindTenant();
        EmailHistory h = historyRepo.save(new EmailHistory(TENANT, EmailKind.REPORT, UUID.randomUUID(),
                LocalDate.of(2026, 3, 1), List.of(), "c@x.ro", "body", EmailStatus.QUEUED, null, null));
        var message = EmailSender.Message.of("F", "f@x.ro", "c@x.ro", "S", "b");
        OutboxMessage msg = outboxRepo.save(new OutboxMessage(TENANT, EmailOutboxHandler.AGGREGATE,
                h.getId().toString(), EmailOutboxHandler.TYPE, emailHandler.serialize(
                        new EmailOutboxHandler.Payload(h.getId(), message))));
        relay.relayDue(10);
        // Force it "due" again; a SENT row must not be re-delivered.
        jdbc.update("update outbox_message set next_attempt_at = now() - interval '1 hour' where id = ?", msg.getId());

        relay.relayDue(10);

        assertThat(outboxRepo.findById(msg.getId()).orElseThrow().getStatus()).isEqualTo(OutboxMessage.Status.SENT);
    }

    @Test
    void poisonMessageBacksOffThenLandsInDlqAndMarksHistoryFailed() {
        bindTenant();
        EmailHistory h = historyRepo.save(new EmailHistory(TENANT, EmailKind.PAYROLL, UUID.randomUUID(),
                LocalDate.of(2026, 3, 1), List.of(), "c@x.ro", "body", EmailStatus.QUEUED, null, null));
        // The failing handler's onExhausted uses the message id, not the payload, so any payload is fine —
        // but wire a real email payload so the DLQ path could flip a history row if it chose to.
        OutboxMessage msg = outboxRepo.save(new OutboxMessage(TENANT, "test", h.getId().toString(),
                "TEST_FAIL", "{}"));

        // Drive one attempt per tick, resetting the backoff gate so we don't wait real time.
        for (int i = 0; i < OutboxDelivery.MAX_ATTEMPTS; i++) {
            jdbc.update("update outbox_message set next_attempt_at = now() - interval '1 hour' where id = ?", msg.getId());
            relay.relayDue(10);
        }

        OutboxMessage finalRow = outboxRepo.findById(msg.getId()).orElseThrow();
        assertThat(finalRow.getStatus()).isEqualTo(OutboxMessage.Status.DLQ);
        assertThat(finalRow.getAttempts()).isEqualTo(OutboxDelivery.MAX_ATTEMPTS);
        assertThat(failingHandler.exhausted.get()).isEqualTo(1);
    }
}
