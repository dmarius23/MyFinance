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
import ro.myfinance.extraction.adapter.web.BankStatementDtos.InvoicePaymentsResponse;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.InvoiceResponse;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.OpenInvoiceResponse;
import ro.myfinance.extraction.application.ReconciliationService;

/** Invoices for a company/period (manual-link candidates). Firm staff only. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/invoices")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class InvoiceController {

    private final ReconciliationService reconciliation;

    public InvoiceController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping
    public List<InvoiceResponse> list(@PathVariable UUID companyId,
                                      @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return reconciliation.invoicesForPeriod(companyId, period).stream()
                .map(InvoiceResponse::from).toList();
    }

    /** Invoices still open for payment within a rolling window (default 18 months) ending at period. */
    @GetMapping("/open")
    public List<OpenInvoiceResponse> open(@PathVariable UUID companyId,
                                          @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
                                          @RequestParam(value = "months", defaultValue = "18") int months,
                                          @RequestParam(value = "includeMapped", defaultValue = "false") boolean includeMapped) {
        return reconciliation.openInvoices(companyId, period, months, includeMapped).stream()
                .map(OpenInvoiceResponse::from).toList();
    }

    /** Invoice-centric payments view (its applied payments + remaining), keyed by the document id. */
    @GetMapping("/by-document/{documentId}/payments")
    public InvoicePaymentsResponse paymentsByDocument(@PathVariable UUID companyId, @PathVariable UUID documentId) {
        return InvoicePaymentsResponse.from(reconciliation.invoicePaymentsByDocument(companyId, documentId));
    }
}
