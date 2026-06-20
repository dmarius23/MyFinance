package ro.myfinance.taxpayments.adapter.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.taxpayments.application.TaxEmailService;
import ro.myfinance.taxpayments.application.TaxPaymentService;
import ro.myfinance.taxpayments.application.TaxPaymentService.Computation;
import ro.myfinance.taxpayments.domain.TaxPaymentSummary;
import ro.myfinance.taxpayments.domain.TaxPaymentSummary.EmailView;
import ro.myfinance.taxpayments.domain.TaxPaymentSummary.Unconfigured;

/** MOD-07 — computed tax payment summary, email preview, and email send/history. Firm staff only. */
@RestController
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class TaxPaymentController {

    private final TaxPaymentService payments;
    private final TaxEmailService emails;

    public TaxPaymentController(TaxPaymentService payments, TaxEmailService emails) {
        this.payments = payments;
        this.emails = emails;
    }

    @GetMapping("/api/v1/tax-payments")
    public List<ro.myfinance.taxpayments.domain.TaxPaymentRow> list(
            @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return payments.list(period);
    }

    @GetMapping("/api/v1/companies/{companyId}/tax-payments")
    public TaxPaymentSummary summary(@PathVariable UUID companyId,
                                     @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return payments.summary(companyId, period);
    }

    /** Default editable email body for the chosen declarations. */
    @PostMapping("/api/v1/companies/{companyId}/tax-emails/preview")
    public PreviewResponse preview(@PathVariable UUID companyId, @Valid @RequestBody PreviewRequest r) {
        Computation c = emails.preview(companyId, r.declarationIds());
        return new PreviewResponse(c.body(), c.total(), c.unconfigured());
    }

    /** Record + send an email for the chosen declarations with the (edited) body. */
    @PostMapping("/api/v1/companies/{companyId}/tax-emails")
    public EmailView send(@PathVariable UUID companyId, @Valid @RequestBody SendRequest r) {
        return EmailView.from(emails.send(companyId, r.period(), r.declarationIds(), r.recipient(), r.body()));
    }

    public record PreviewRequest(@NotEmpty List<UUID> declarationIds) {
    }

    public record PreviewResponse(String body, BigDecimal total, List<Unconfigured> unconfigured) {
    }

    public record SendRequest(@NotNull LocalDate period, @NotEmpty List<UUID> declarationIds,
                              String recipient, @NotNull String body) {
    }
}
