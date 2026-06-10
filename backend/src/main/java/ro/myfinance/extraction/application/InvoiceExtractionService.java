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
        LocalDate period = periodMonth.withDayOfMonth(1);
        // Upsert by document: re-scan updates the existing row in place (preserving its id and any
        // matches) rather than delete+insert, which would break match FKs and trip the unique
        // document_id (Hibernate orders the insert before the delete within a shared transaction).
        invoices.findByDocumentId(documentId).ifPresentOrElse(
                existing -> existing.updateExtraction(p.supplierName(), p.supplierIban(), p.totalAmount(),
                        p.invoiceDate(), filename, status),
                () -> invoices.save(new Invoice(tenantId, documentId, companyId, period,
                        p.supplierName(), p.supplierIban(), p.totalAmount(), p.invoiceDate(), filename, status)));
        reconciliation.matchPeriod(companyId, period);
    }
}
