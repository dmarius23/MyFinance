package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.extraction.adapter.external.ReceiptProperties;
import ro.myfinance.extraction.adapter.persistence.InvoiceRepository;
import ro.myfinance.extraction.domain.Invoice;

/** Extracts an uploaded invoice/receipt and triggers (re)matching for its company+period. */
@Service
@Transactional
public class InvoiceExtractionService {

    private final InvoiceExtractor extractor;
    private final ReceiptExtractor receipts;
    private final ReceiptProperties receiptProps;
    private final InvoiceRepository invoices;
    private final ReconciliationService reconciliation;
    private final CompanyRepository companies;

    public InvoiceExtractionService(InvoiceExtractor extractor, ReceiptExtractor receipts,
                                    ReceiptProperties receiptProps, InvoiceRepository invoices,
                                    ReconciliationService reconciliation, CompanyRepository companies) {
        this.extractor = extractor;
        this.receipts = receipts;
        this.receiptProps = receiptProps;
        this.invoices = invoices;
        this.reconciliation = reconciliation;
        this.companies = companies;
    }

    /** Extracted fields, from either the PDF parser or the receipt-image OCR/LLM. */
    private record Fields(String supplierName, String supplierIban, BigDecimal total, LocalDate date,
                          String status, String issuerCif, String clientCif, String receiptNumber,
                          Boolean wrongParty) {
    }

    public void process(UUID documentId, UUID companyId, LocalDate periodMonth, String filename, byte[] bytes) {
        UUID tenantId = TenantContext.tenantId().orElseThrow(() -> new IllegalStateException("No tenant bound"));
        var company = companies.findById(companyId);
        // The document belongs to this company, which is the buyer — exclude it from supplier detection.
        String ownName = company.map(c -> c.getLegalName()).orElse(null);
        String ownCui = company.map(c -> c.getCui()).orElse(null);
        LocalDate period = periodMonth.withDayOfMonth(1);

        final Fields f = isPdf(bytes) ? fromPdf(bytes, ownName, ownCui) : fromReceiptImage(bytes, filename, ownCui);

        // Upsert by document: re-scan updates the existing row in place (preserving its id and any
        // matches) rather than delete+insert, which would break match FKs and trip the unique
        // document_id (Hibernate orders the insert before the delete within a shared transaction).
        invoices.findByDocumentId(documentId).ifPresentOrElse(
                existing -> {
                    existing.updateExtraction(f.supplierName(), f.supplierIban(), f.total(), f.date(), filename, f.status());
                    existing.updateReceiptFields(f.issuerCif(), f.clientCif(), f.receiptNumber(), f.wrongParty());
                },
                () -> {
                    Invoice inv = new Invoice(tenantId, documentId, companyId, period,
                            f.supplierName(), f.supplierIban(), f.total(), f.date(), filename, f.status());
                    inv.updateReceiptFields(f.issuerCif(), f.clientCif(), f.receiptNumber(), f.wrongParty());
                    invoices.save(inv);
                });
        reconciliation.matchPeriod(companyId, period);
    }

    private Fields fromPdf(byte[] bytes, String ownName, String ownCui) {
        ParsedInvoice p = extractor.extract(bytes, ownName);
        String status = (p.supplierIban() != null && p.totalAmount() != null) ? "EXTRACTED" : "NEEDS_REVIEW";
        // Invoice client CIF is checksum-valid (extractor guarantees), so a direct digit compare is sound.
        Boolean wrongParty = wrongPartyFromCif(p.clientCif(), ownCui);
        return new Fields(p.supplierName(), p.supplierIban(), p.totalAmount(), p.invoiceDate(), status,
                null, p.clientCif(), null, wrongParty);
    }

    private Fields fromReceiptImage(byte[] bytes, String filename, String ownCui) {
        ParsedReceipt r = receipts.extract(bytes, mediaType(filename, bytes), ownCui);
        boolean ok = r.total() != null && r.issueDate() != null && r.confidence() >= receiptProps.confidenceThreshold();
        // Receipt CIFs are easily misread; trust the model's match verdict (tolerant of a misread digit).
        Boolean wrongParty = r.clientMatchesCompany() == null ? null : !r.clientMatchesCompany();
        return new Fields(r.issuerName(), null, r.total(), r.issueDate(), ok ? "EXTRACTED" : "NEEDS_REVIEW",
                r.issuerCif(), r.clientCif(), r.receiptNumber(), wrongParty);
    }

    /** true when both codes are present and differ, false when they match, null when undeterminable. */
    private static Boolean wrongPartyFromCif(String clientCif, String ownCui) {
        String a = RoFiscalCode.digits(clientCif);
        String b = RoFiscalCode.digits(ownCui);
        if (a == null || b == null) {
            return null;
        }
        return !a.equals(b);
    }

    private static boolean isPdf(byte[] b) {
        return b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
    }

    /** Best-effort image media type from magic bytes, falling back to the filename extension. */
    private static String mediaType(String filename, byte[] b) {
        if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        String name = filename == null ? "" : filename.toLowerCase();
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }
}
