package ro.myfinance.taxpayments.adapter.web;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.taxpayments.application.TaxPaymentService;
import ro.myfinance.taxpayments.domain.TaxPaymentSummary;

/** MOD-07 — computed tax payment summary per company + period. Firm staff only. */
@RestController
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class TaxPaymentController {

    private final TaxPaymentService service;

    public TaxPaymentController(TaxPaymentService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/companies/{companyId}/tax-payments")
    public TaxPaymentSummary summary(@PathVariable UUID companyId,
                                     @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return service.summary(companyId, period);
    }
}
