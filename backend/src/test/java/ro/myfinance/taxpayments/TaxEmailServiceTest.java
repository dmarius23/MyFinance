package ro.myfinance.taxpayments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import ro.myfinance.taxpayments.adapter.persistence.TaxEmailRepository;
import ro.myfinance.taxpayments.application.EmailSender;
import ro.myfinance.taxpayments.application.TaxEmailService;
import ro.myfinance.taxpayments.application.TaxPaymentService;
import ro.myfinance.taxpayments.domain.TaxEmail;

/** Verifies that every send is recorded — SENT on success, FAILED with the error otherwise. */
class TaxEmailServiceTest {

    private final TaxPaymentService payments = mock(TaxPaymentService.class);
    private final TaxEmailRepository repo = mock(TaxEmailRepository.class);
    private final EmailSender sender = mock(EmailSender.class);
    private final ro.myfinance.access.application.EmailEnvelopeService envelopes =
            mock(ro.myfinance.access.application.EmailEnvelopeService.class);
    private final TaxEmailService service = new TaxEmailService(payments, repo, sender, envelopes);

    @BeforeEach
    void bindTenant() {
        TenantContext.set(new TenantContext.Identity(UUID.randomUUID(), UUID.randomUUID(), Role.TENANT_ADMIN, null));
        when(repo.save(any(TaxEmail.class))).thenAnswer(i -> i.getArgument(0));
        when(envelopes.resolve(any(), any())).thenAnswer(i -> new ro.myfinance.access.application
                .EmailEnvelopeService.Envelope("Maria Pop", "firma@contabil.ro", i.getArgument(1)));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void recordsSentOnSuccess() {
        TaxEmail saved = service.send(UUID.randomUUID(), LocalDate.of(2026, 3, 10),
                List.of(UUID.randomUUID()), "client@example.com", "Bună ziua, ...");
        assertThat(saved.getStatus()).isEqualTo(TaxEmail.Status.SENT);
        assertThat(saved.getError()).isNull();
        assertThat(saved.getPeriodMonth()).isEqualTo(LocalDate.of(2026, 3, 1)); // normalized to first of month
        assertThat(saved.getRecipient()).isEqualTo("client@example.com");
    }

    @Test
    void recordsFailedWhenSenderThrows() {
        doThrow(new RuntimeException("SES down")).when(sender).send(any(EmailSender.Message.class));
        TaxEmail saved = service.send(UUID.randomUUID(), LocalDate.of(2026, 3, 10),
                List.of(UUID.randomUUID()), "client@example.com", "body");
        assertThat(saved.getStatus()).isEqualTo(TaxEmail.Status.FAILED);
        assertThat(saved.getError()).isEqualTo("SES down");
    }
}
