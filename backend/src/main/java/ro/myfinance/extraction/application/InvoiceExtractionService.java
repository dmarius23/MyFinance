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
    record Fields(String supplierName, String supplierIban, BigDecimal total, LocalDate date,
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

        final Fields f;
        if (isPdf(bytes)) {
            // Deterministic text parse first — authoritative whenever it succeeds. OCR only fills the gap:
            //  • text unreadable / nothing usable → full OCR of the key pages (header + totals).
            //  • text readable and carries the money fields but has no supplier (image-only logo) → OCR
            //    page 1 alone for the supplier and merge just the name/CIF into the text fields.
            //  • text parse complete → no OCR at all, however many logos the page carries.
            Fields text = fromPdf(bytes, ownName, ownCui);
            boolean supplierMissing = isBlank(text.supplierName()) && isBlank(text.issuerCif());
            boolean textReadable = ro.myfinance.common.pdf.PdfImages.isTextReadable(bytes);
            boolean needOcr = receiptProps.isAnthropic() && (supplierMissing || !textReadable);

            if (!needOcr) {
                f = text;
            } else if (textReadable && text.total() != null) {
                // Hybrid: keep the text-derived money/date/party; OCR page 1 only for the supplier.
                byte[] page1 = ro.myfinance.common.pdf.PdfImages.renderFirstPagePng(bytes, 200);
                Fields ocr = page1 != null
                        ? identifyClientParty(fromReceiptImage(page1, "page.png", ownCui), ownCui, ownName) : null;
                f = ocr != null ? mergeSupplier(text, ocr) : text;
            } else {
                // Nothing trustworthy from text → full OCR of the header + totals pages (first + last).
                java.util.List<byte[]> pages = ro.myfinance.common.pdf.PdfImages.renderFirstAndLastPng(bytes, 200);
                f = !pages.isEmpty()
                        ? identifyClientParty(fromReceiptImages(pages, "image/png", ownCui), ownCui, ownName)
                        : text;
            }
        } else {
            f = fromReceiptImage(bytes, filename, ownCui);
        }

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
                p.issuerCif(), p.clientCif(), p.invoiceNumber(), wrongParty);
    }

    private Fields fromReceiptImage(byte[] bytes, String filename, String ownCui) {
        return fromReceiptImages(java.util.List.of(bytes), mediaType(filename, bytes), ownCui);
    }

    private Fields fromReceiptImages(java.util.List<byte[]> images, String mediaType, String ownCui) {
        ParsedReceipt r = receipts.extract(images, mediaType, ownCui);
        boolean ok = r.total() != null && r.issueDate() != null && r.confidence() >= receiptProps.confidenceThreshold();
        // Receipt CIFs are easily misread; trust the model's match verdict (tolerant of a misread digit).
        Boolean wrongParty = r.clientMatchesCompany() == null ? null : !r.clientMatchesCompany();
        return new Fields(r.issuerName(), null, r.total(), r.issueDate(), ok ? "EXTRACTED" : "NEEDS_REVIEW",
                r.issuerCif(), r.clientCif(), r.receiptNumber(), wrongParty);
    }

    /**
     * Vision OCR of a purchase invoice can mislabel the buyer (our company) as the issuer. When our own
     * CUI is printed on the document (whether the model called it client or issuer), the company is the
     * client/buyer → it's our invoice (identified, correct party); the company is never the supplier.
     * If the model swapped the parties (tagged us as the issuer), recover the supplier's CIF from the
     * value it called "client" and drop the buyer-as-supplier name.
     */
    static Fields identifyClientParty(Fields f, String ownCui, String ownName) {
        String own = RoFiscalCode.digits(ownCui);
        boolean ownIsIssuer = own != null && own.equals(RoFiscalCode.digits(f.issuerCif()));
        boolean ownIsClient = own != null && own.equals(RoFiscalCode.digits(f.clientCif()));
        if (!ownIsIssuer && !ownIsClient) {
            return f;
        }
        // On a swap (own == issuer), the code the model called "client" is really the supplier's CIF.
        String otherClient = RoFiscalCode.digits(f.clientCif());
        String issuerCif = ownIsIssuer
                ? (otherClient != null && !otherClient.equals(own) ? f.clientCif() : null)
                : f.issuerCif();
        // Drop a supplier name that is actually the buyer (matched our CUI as issuer, or matches our name).
        boolean supplierIsUs = ownIsIssuer
                || (f.supplierName() != null && ownName != null && f.supplierName().trim().equalsIgnoreCase(ownName.trim()));
        String supplier = supplierIsUs ? null : f.supplierName();
        return new Fields(supplier, f.supplierIban(), f.total(), f.date(), f.status(),
                issuerCif, ownCui, f.receiptNumber(), Boolean.FALSE);
    }

    /**
     * Hybrid merge: take only the supplier identity (name + fiscal code) from the page-1 OCR, keeping the
     * total, date, buyer and status from the trustworthy text parse. Used when the PDF text is readable
     * (so the money fields are sound) but the supplier is image-only (a logo).
     */
    static Fields mergeSupplier(Fields text, Fields ocr) {
        String supplierName = isBlank(text.supplierName()) ? ocr.supplierName() : text.supplierName();
        String issuerCif = isBlank(text.issuerCif()) ? ocr.issuerCif() : text.issuerCif();
        return new Fields(supplierName, text.supplierIban(), text.total(), text.date(), text.status(),
                issuerCif, text.clientCif(), text.receiptNumber(), text.wrongParty());
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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
