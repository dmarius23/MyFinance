package ro.myfinance.access.adapter.web;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.access.application.EmailEnvelopeService;
import ro.myfinance.access.application.EmailEnvelopeService.Envelope;

/**
 * The default email participants for a company — From name (logged-in user), From email (accounting
 * firm), and recipient (the company's representative). Used by every email modal to prefill the
 * recipient and show the From line. Firm staff only.
 */
@RestController
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class EmailEnvelopeController {

    private final EmailEnvelopeService envelopes;

    public EmailEnvelopeController(EmailEnvelopeService envelopes) {
        this.envelopes = envelopes;
    }

    @GetMapping("/api/v1/companies/{companyId}/email-envelope")
    public EnvelopeResponse get(@PathVariable UUID companyId) {
        Envelope e = envelopes.resolve(companyId, null);
        return new EnvelopeResponse(e.fromName(), e.fromEmail(), e.recipient());
    }

    public record EnvelopeResponse(String fromName, String fromEmail, String recipient) {
    }
}
