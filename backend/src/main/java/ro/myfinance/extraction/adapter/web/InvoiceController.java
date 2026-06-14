package ro.myfinance.extraction.adapter.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.extraction.adapter.persistence.InvoiceRepository;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.InvoiceResponse;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.OpenInvoiceResponse;
import ro.myfinance.extraction.application.ReconciliationService;

/** Invoices for a company/period (manual-link candidates). Firm staff only. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/invoices")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class InvoiceController {

    private final InvoiceRepository invoices;
    private final ReconciliationService reconciliation;

    public InvoiceController(InvoiceRepository invoices, ReconciliationService reconciliation) {
        this.invoices = invoices;
        this.reconciliation = reconciliation;
    }

    @GetMapping
    public List<InvoiceResponse> list(@PathVariable UUID companyId,
                                      @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return invoices.findByCompanyIdAndPeriodMonth(companyId, period).stream()
                .map(InvoiceResponse::from).toList();
    }

    /** Invoices still open for payment within a rolling window (default 18 months) ending at period. */
    @GetMapping("/open")
    public List<OpenInvoiceResponse> open(@PathVariable UUID companyId,
                                          @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
                                          @RequestParam(value = "months", defaultValue = "18") int months) {
        return reconciliation.openInvoices(companyId, period, months).stream()
                .map(OpenInvoiceResponse::from).toList();
    }
}
