package ro.myfinance.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.domain.DocumentType;
import ro.myfinance.payroll.adapter.persistence.PayrollEmailRepository;
import ro.myfinance.payroll.application.PayrollEmailBuilder;
import ro.myfinance.payroll.application.PayrollService;
import ro.myfinance.payroll.application.PayrollService.PayrollEmailView;
import ro.myfinance.payroll.domain.PayrollEmail;
import ro.myfinance.taxpayments.application.EmailSender;

/** Verifies payroll sends are recorded (SENT/FAILED) and the standard RO body is well-formed. */
class PayrollServiceTest {

    private final DocumentService documents = mock(DocumentService.class);
    private final PayrollEmailRepository repo = mock(PayrollEmailRepository.class);
    private final EmailSender sender = mock(EmailSender.class);
    private final ro.myfinance.access.application.EmailEnvelopeService envelopes =
            mock(ro.myfinance.access.application.EmailEnvelopeService.class);
    private final PayrollService service = new PayrollService(documents, repo, sender, envelopes);

    @BeforeEach
    void bindTenant() {
        TenantContext.set(new TenantContext.Identity(UUID.randomUUID(), UUID.randomUUID(), Role.TENANT_ADMIN, null));
        when(documents.listByCompanyPeriodType(any(), any(), any())).thenReturn(List.of());
        when(repo.save(any(PayrollEmail.class))).thenAnswer(i -> i.getArgument(0));
        when(envelopes.resolve(any(), any())).thenAnswer(i -> new ro.myfinance.access.application
                .EmailEnvelopeService.Envelope("Maria Pop", "firma@contabil.ro", i.getArgument(1)));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void recordsSentOnSuccess() {
        PayrollEmailView saved = service.send(UUID.randomUUID(), LocalDate.of(2026, 4, 10),
                "client@example.com", "Bună ziua, ...");
        assertThat(saved.status()).isEqualTo(PayrollEmail.Status.SENT);
        assertThat(saved.sentAt()).isNotNull();
        assertThat(saved.recipient()).isEqualTo("client@example.com");
    }

    @Test
    void recordsFailedWhenSenderThrows() {
        doThrow(new RuntimeException("SES down")).when(sender)
                .send(any(EmailSender.Message.class));
        PayrollEmailView saved = service.send(UUID.randomUUID(), LocalDate.of(2026, 4, 10),
                "client@example.com", "body");
        assertThat(saved.status()).isEqualTo(PayrollEmail.Status.FAILED);
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
