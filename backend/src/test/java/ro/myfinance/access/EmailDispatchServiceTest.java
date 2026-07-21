package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import ro.myfinance.common.email.EmailSender;
import ro.myfinance.common.email.EmailStatus;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;

/**
 * The shared resolve → send → record flow: every dispatch appends exactly one history row (SENT on
 * success, FAILED with the error otherwise) and only runs the {@code onSent} hook when the send succeeds.
 */
class EmailDispatchServiceTest {

    private final EmailEnvelopeService envelopes = mock(EmailEnvelopeService.class);
    private final EmailSender sender = mock(EmailSender.class);
    private final EmailHistoryRepository history = mock(EmailHistoryRepository.class);
    private final EmailDispatchService dispatch = new EmailDispatchService(envelopes, sender, history);

    @BeforeEach
    void bind() {
        TenantContext.set(new TenantContext.Identity(UUID.randomUUID(), UUID.randomUUID(), Role.TENANT_ADMIN, null));
        when(history.save(any(EmailHistory.class))).thenAnswer(i -> i.getArgument(0));
        when(envelopes.resolve(any(), any())).thenAnswer(i -> new EmailEnvelopeService.Envelope(
                "Maria Pop", "firma@contabil.ro", i.getArgument(1)));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void recordsSentAndRunsOnSentHook() {
        List<UUID> related = List.of(UUID.randomUUID());
        boolean[] ran = {false};

        EmailHistory row = dispatch.dispatch(EmailKind.TAX, UUID.randomUUID(), LocalDate.of(2026, 3, 10),
                "client@example.com", "Subject", "body", null, related, () -> ran[0] = true);

        assertThat(row.getStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(row.getError()).isNull();
        assertThat(row.getKind()).isEqualTo(EmailKind.TAX);
        assertThat(row.getPeriodMonth()).isEqualTo(LocalDate.of(2026, 3, 1)); // normalized to first of month
        assertThat(row.getRecipient()).isEqualTo("client@example.com");
        assertThat(row.getRelatedIds()).isEqualTo(related);
        assertThat(ran[0]).as("onSent runs on success").isTrue();
    }

    @Test
    void recordsFailedAndSkipsOnSentWhenSenderThrows() {
        doThrow(new RuntimeException("SES down")).when(sender).send(any(EmailSender.Message.class));
        boolean[] ran = {false};

        EmailHistory row = dispatch.dispatch(EmailKind.REPORT, UUID.randomUUID(), LocalDate.of(2026, 3, 10),
                "client@example.com", "Subject", "body", null, null, () -> ran[0] = true);

        assertThat(row.getStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(row.getError()).isEqualTo("SES down");
        assertThat(row.getRelatedIds()).isEmpty();
        assertThat(ran[0]).as("onSent must not run on failure").isFalse();
        verify(sender).send(any(EmailSender.Message.class));
    }

    @Test
    void alwaysSavesExactlyOneRow() {
        dispatch.dispatch(EmailKind.DOCUMENT_REMINDER, UUID.randomUUID(), LocalDate.of(2026, 3, 1),
                null, "s", "b", null, null, null);
        verify(history).save(any(EmailHistory.class));
        verify(sender).send(any(EmailSender.Message.class));
        verify(history, never()).delete(any());
    }
}
