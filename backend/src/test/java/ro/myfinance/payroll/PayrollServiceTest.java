package ro.myfinance.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.access.application.EmailDispatchService;
import ro.myfinance.common.email.EmailHistory;
import ro.myfinance.common.email.EmailHistoryRepository;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.email.EmailStatus;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.payroll.application.PayrollEmailBuilder;
import ro.myfinance.payroll.application.PayrollService;
import ro.myfinance.payroll.application.PayrollService.PayrollEmailView;

/** Payroll delegates the send to the shared dispatch (kind=PAYROLL) and the standard RO body is well-formed. */
class PayrollServiceTest {

    private final DocumentService documents = mock(DocumentService.class);
    private final EmailHistoryRepository history = mock(EmailHistoryRepository.class);
    private final EmailDispatchService dispatch = mock(EmailDispatchService.class);
    private final ro.myfinance.access.application.EmailEnvelopeService envelopes =
            mock(ro.myfinance.access.application.EmailEnvelopeService.class);
    private final ro.myfinance.notifications.application.NotificationService notifications =
            mock(ro.myfinance.notifications.application.NotificationService.class);
    private final PayrollService service =
            new PayrollService(documents, history, dispatch, envelopes, notifications);

    @Test
    void sendDelegatesToDispatchWithPayrollKindAndMapsTheRow() {
        UUID companyId = UUID.randomUUID();
        when(documents.listByCompanyPeriodType(any(), any(), any())).thenReturn(List.of());
        EmailHistory row = new EmailHistory(UUID.randomUUID(), EmailKind.PAYROLL, companyId,
                LocalDate.of(2026, 4, 1), List.of(), "client@example.com", "body", EmailStatus.SENT, null, null);
        when(dispatch.dispatch(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(row);

        PayrollEmailView saved = service.send(companyId, LocalDate.of(2026, 4, 10),
                "client@example.com", "body", null);

        assertThat(saved.status()).isEqualTo(EmailStatus.SENT);
        assertThat(saved.recipient()).isEqualTo("client@example.com");
        verify(dispatch).dispatch(eq(EmailKind.PAYROLL), eq(companyId), eq(LocalDate.of(2026, 4, 10)),
                eq("client@example.com"), any(String.class), eq("body"), any(), any(), any());
    }

    @Test
    void composesStandardRomanianBody() {
        String body = PayrollEmailBuilder.body(LocalDate.of(2026, 4, 1), "Maria Pop");
        assertThat(body).contains("luna Aprilie 2026");
        assertThat(body).contains("statul de plată, fluturașul de salariu și pontajul");
        assertThat(body).contains("până în data de 25 Aprilie 2026");
        assertThat(body).contains("Maria Pop");
        assertThat(PayrollEmailBuilder.subject(LocalDate.of(2026, 4, 1))).isEqualTo("State de plată — Aprilie 2026");
    }

    @Test
    void bodyUsesPlaceholderWhenNoAccountantName() {
        assertThat(PayrollEmailBuilder.body(LocalDate.of(2026, 4, 1), null)).contains("[Numele contabilului]");
    }
}
