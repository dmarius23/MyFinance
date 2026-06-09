package ro.myfinance.extraction.application;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.extraction.adapter.persistence.InvoiceRepository;
import ro.myfinance.extraction.domain.Invoice;

/** Extracts an uploaded invoice and triggers (re)matching for its company+period. */
@Service
@Transactional
public class InvoiceExtractionService {

    private final InvoiceExtractor extractor;
    private final InvoiceRepository invoices;
    private final ReconciliationService reconciliation;

    public InvoiceExtractionService(InvoiceExtractor extractor, InvoiceRepository invoices,
                                    ReconciliationService reconciliation) {
        this.extractor = extractor;
        this.invoices = invoices;
        this.reconciliation = reconciliation;
    }

    public void process(UUID documentId, UUID companyId, LocalDate periodMonth, String filename, byte[] bytes) {
        UUID tenantId = TenantContext.tenantId().orElseThrow(() -> new IllegalStateException("No tenant bound"));
        ParsedInvoice p = extractor.extract(bytes);
        String status = (p.supplierIban() != null && p.totalAmount() != null) ? "EXTRACTED" : "NEEDS_REVIEW";
        invoices.save(new Invoice(tenantId, documentId, companyId, periodMonth.withDayOfMonth(1),
                p.supplierName(), p.supplierIban(), p.totalAmount(), p.invoiceDate(), filename, status));
        reconciliation.matchPeriod(companyId, periodMonth.withDayOfMonth(1));
    }
}
