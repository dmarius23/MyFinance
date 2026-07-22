package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.myfinance.access.application.EmailDispatchService;
import ro.myfinance.access.application.EmailEnvelopeService;
import ro.myfinance.common.email.EmailHistory;
import ro.myfinance.common.email.EmailHistoryRepository;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.email.EmailOutboxHandler;
import ro.myfinance.common.email.EmailStatus;
import ro.myfinance.common.outbox.OutboxWriter;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;

/**
 * The shared resolve → record → enqueue flow: every dispatch records exactly one QUEUED history row, writes
 * a SEND_EMAIL outbox message in the same transaction, and runs the {@code onSent} hook once queued. Actual
 * delivery (QUEUED → SENT/FAILED) is the relay + {@code EmailOutboxHandler}'s job, covered elsewhere.
 */
class EmailDispatchServiceTest {

    private final EmailEnvelopeService envelopes = mock(EmailEnvelopeService.class);
    private final EmailHistoryRepository history = mock(EmailHistoryRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final EmailOutboxHandler emailOutbox = mock(EmailOutboxHandler.class);
    private final EmailDispatchService dispatch =
            new EmailDispatchService(envelopes, history, outbox, emailOutbox);

    @BeforeEach
    void bind() {
        TenantContext.set(new TenantContext.Identity(UUID.randomUUID(), UUID.randomUUID(), Role.TENANT_ADMIN, null));
        when(history.save(any(EmailHistory.class))).thenAnswer(i -> {
            EmailHistory h = i.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(h, "id", UUID.randomUUID()); // JPA would assign it
            return h;
        });
        when(envelopes.resolve(any(), any())).thenAnswer(i -> new EmailEnvelopeService.Envelope(
                "Maria Pop", "firma@contabil.ro", i.getArgument(1)));
        when(emailOutbox.serialize(any())).thenReturn("{}");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void recordsQueuedEnqueuesAndRunsOnSentHook() {
        List<UUID> related = List.of(UUID.randomUUID());
        boolean[] ran = {false};

        EmailHistory row = dispatch.dispatch(EmailKind.TAX, UUID.randomUUID(), LocalDate.of(2026, 3, 10),
                "client@example.com", "Subject", "body", null, related, () -> ran[0] = true);

        assertThat(row.getStatus()).isEqualTo(EmailStatus.QUEUED);
        assertThat(row.getKind()).isEqualTo(EmailKind.TAX);
        assertThat(row.getPeriodMonth()).isEqualTo(LocalDate.of(2026, 3, 1)); // normalized to first of month
        assertThat(row.getRecipient()).isEqualTo("client@example.com");
        assertThat(row.getRelatedIds()).isEqualTo(related);
        assertThat(ran[0]).as("onSent runs once durably queued").isTrue();
        // One history row + one SEND_EMAIL outbox message, same transaction.
        verify(history).save(any(EmailHistory.class));
        verify(outbox).enqueue(eq(EmailOutboxHandler.AGGREGATE), any(), eq(EmailOutboxHandler.TYPE), any());
    }

    @Test
    void emptyRelatedIdsWhenNullPassed() {
        EmailHistory row = dispatch.dispatch(EmailKind.DOCUMENT_REMINDER, UUID.randomUUID(),
                LocalDate.of(2026, 3, 1), null, "s", "b", null, null, null);
        assertThat(row.getRelatedIds()).isEmpty();
        assertThat(row.getStatus()).isEqualTo(EmailStatus.QUEUED);
        verify(outbox).enqueue(any(), any(), eq(EmailOutboxHandler.TYPE), any());
    }
}
