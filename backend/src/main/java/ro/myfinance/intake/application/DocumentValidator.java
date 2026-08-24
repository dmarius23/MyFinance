package ro.myfinance.intake.application;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.myfinance.common.hash.ContentHash;
import ro.myfinance.company.domain.Company;
import ro.myfinance.extraction.application.BankStatementParser;
import ro.myfinance.extraction.application.BankStatementParserRegistry;
import ro.myfinance.extraction.application.InvoiceExtractor;
import ro.myfinance.extraction.application.ParsedInvoice;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;
import ro.myfinance.ingestion.application.FolderMapper;
import ro.myfinance.intake.adapter.persistence.DocumentRepository;
import ro.myfinance.intake.domain.DocumentType;
import ro.myfinance.intake.domain.DriveBlockReason;
import ro.myfinance.taxpayments.application.AnafDeclarationExtractor;
import ro.myfinance.taxpayments.domain.ParsedDeclaration;
import ro.myfinance.taxpayments.domain.TaxObligation;

/**
 * One common validation for every uploaded document: is it a <b>duplicate</b>, is it for the
 * <b>wrong company</b>, or is it filed under the <b>wrong month</b>? The verdict drives the Drive mirror —
 * a blocked document is still stored on the server but is never written to Drive.
 *
 * <p>Detection reuses the existing deterministic extractors (ANAF XML, e-Factura/invoice, bank-statement
 * parsers, the payroll company-text check). The overriding rule is <b>fail open</b>: block only on a
 * confident mismatch; whenever content can't be read or is ambiguous, the document stays eligible so good
 * files always get filed. Also captures the declaration routing metadata (form + dominant obligation) so
 * the mirror can place a declaration without re-parsing.
 */
@Component
public class DocumentValidator {

    private static final Logger log = LoggerFactory.getLogger(DocumentValidator.class);

    private final DocumentRepository documents;
    private final AnafDeclarationExtractor declarations;
    private final InvoiceExtractor invoices;
    private final BankStatementParserRegistry statements;

    public DocumentValidator(DocumentRepository documents, AnafDeclarationExtractor declarations,
                             InvoiceExtractor invoices, BankStatementParserRegistry statements) {
        this.documents = documents;
        this.declarations = declarations;
        this.invoices = invoices;
        this.statements = statements;
    }

    /** The validation outcome + Drive-routing metadata to persist on the document. */
    public record Result(String contentSha256, DriveBlockReason blockReason, String blockDetail,
                         String declKind, String dominantObligationCod) {
        public boolean blocked() {
            return blockReason != null;
        }
    }

    /**
     * Validate a document about to be stored. {@code excludeDocId} is the id of the document being
     * re-evaluated (move-period / change-type) so it doesn't count itself as a duplicate; null for a fresh
     * upload.
     */
    public Result validate(Company company, LocalDate period, DocumentType type, String filename,
                           String contentType, byte[] bytes, UUID excludeDocId) {
        String sha = ContentHash.sha256(bytes);
        String declKind = null;
        String dominantCod = null;

        if (isDuplicate(company.getId(), period, type, sha, excludeDocId)) {
            return new Result(sha, DriveBlockReason.DUPLICATE,
                    "Fișier identic deja încărcat pentru această firmă și lună.", null, null);
        }

        DriveBlockReason reason = null;
        String detail = null;
        try {
            switch (type) {
                case DECLARATION -> {
                    ParsedDeclaration pd = declarations.extract(bytes);
                    declKind = pd.type() == null ? null : pd.type().name();
                    dominantCod = dominantObligation(pd);
                    if (differentCui(pd.cui(), company.getCui())) {
                        reason = DriveBlockReason.WRONG_COMPANY;
                        detail = "Declarația este pe alt CUI (" + pd.cui() + ") decât firma.";
                    } else if (outsidePeriod(pd.period() == null ? null : pd.period().atDay(1), period)) {
                        reason = DriveBlockReason.WRONG_PERIOD;
                        detail = "Declarația este pentru " + ym(pd.period().atDay(1)) + ", nu " + ym(period) + ".";
                    }
                }
                case INVOICE -> {
                    ParsedInvoice inv = invoices.extract(bytes, company.getLegalName());
                    if (invoiceWrongCompany(inv, company.getCui())) {
                        reason = DriveBlockReason.WRONG_COMPANY;
                        detail = "Factura nu este emisă către/de firmă (CUI " + company.getCui() + " negăsit).";
                    } else if (inv.invoiceDate() != null && outsidePeriod(inv.invoiceDate().withDayOfMonth(1), period)) {
                        reason = DriveBlockReason.WRONG_PERIOD;
                        detail = "Factura este din " + ym(inv.invoiceDate().withDayOfMonth(1)) + ", nu " + ym(period) + ".";
                    }
                }
                case BANK_STATEMENT -> {
                    LocalDate stmtMonth = statementMonth(bytes);
                    if (outsidePeriod(stmtMonth, period)) {
                        reason = DriveBlockReason.WRONG_PERIOD;
                        detail = "Extrasul este pentru " + ym(stmtMonth) + ", nu " + ym(period) + ".";
                    }
                }
                case PAYROLL -> {
                    String text = pdfText(contentType, bytes);
                    if (text != null && Boolean.FALSE.equals(
                            CompanyMatcher.present(text, company.getCui(), company.getLegalName()))) {
                        reason = DriveBlockReason.WRONG_COMPANY;
                        detail = "Documentul nu pare emis pentru această firmă (" + company.getLegalName() + ").";
                    } else {
                        LocalDate filePeriod = FolderMapper.periodFromText(filename).orElse(null);
                        if (outsidePeriod(filePeriod, period)) {
                            reason = DriveBlockReason.WRONG_PERIOD;
                            detail = "Fișierul este pentru " + ym(filePeriod) + ", nu " + ym(period) + ".";
                        }
                    }
                }
                default -> {
                    // TRIAL_BALANCE / RECEIPT / UNCLASSIFIED — duplicate-only (already checked above).
                }
            }
        } catch (RuntimeException ex) {
            // Fail open: unreadable / ambiguous content never blocks a good file from filing.
            log.debug("Validation extraction failed for {} — treating as eligible", filename, ex);
        }
        return new Result(sha, reason, detail, declKind, dominantCod);
    }

    private boolean isDuplicate(UUID companyId, LocalDate period, DocumentType type, String sha, UUID excludeDocId) {
        return excludeDocId == null
                ? documents.existsByCompanyIdAndPeriodMonthAndTypeAndContentSha256(companyId, period, type, sha)
                : documents.existsByCompanyIdAndPeriodMonthAndTypeAndContentSha256AndIdNot(
                        companyId, period, type, sha, excludeDocId);
    }

    /** The largest-amount payable obligation's code (D100 sub-routing), or null. */
    private static String dominantObligation(ParsedDeclaration pd) {
        return pd.obligations().stream()
                .filter(TaxObligation::payable)
                .max(Comparator.comparing(TaxObligation::amount))
                .map(TaxObligation::codOblig)
                .orElse(null);
    }

    /** Wrong party only when both CUIs are known and their digits differ (mirrors TaxDeclarationListener). */
    private static boolean differentCui(String declCui, String ownCui) {
        String a = digits(declCui);
        String b = digits(ownCui);
        return !a.isEmpty() && !b.isEmpty() && !a.equals(b);
    }

    /**
     * An invoice is for the wrong company only when both party fiscal codes are known and neither matches
     * the company's CUI — so a partially-parsed invoice (only one party read) never false-blocks.
     */
    private static boolean invoiceWrongCompany(ParsedInvoice inv, String ownCui) {
        String own = digits(ownCui);
        String issuer = digits(inv.issuerCif());
        String client = digits(inv.clientCif());
        if (own.isEmpty() || issuer.isEmpty() || client.isEmpty()) {
            return false; // can't confidently tell → allow
        }
        return !own.equals(issuer) && !own.equals(client);
    }

    /** The bank statement's own month (dominant transaction month), or null when it can't be parsed. */
    private LocalDate statementMonth(byte[] bytes) {
        String text = statements.extractText(bytes);
        BankStatementParser parser = statements.find(text).orElse(null);
        if (parser == null) {
            return null;
        }
        ParsedStatement parsed = parser.parse(text);
        java.util.Map<LocalDate, Integer> byMonth = new java.util.HashMap<>();
        for (ParsedTransaction t : parsed.transactions()) {
            if (t.date() != null) {
                byMonth.merge(t.date().withDayOfMonth(1), 1, Integer::sum);
            }
        }
        return byMonth.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(null);
    }

    /** Extract text from a PDF for the payroll company check; null when not a readable PDF (can't verify). */
    private static String pdfText(String contentType, byte[] bytes) {
        if (contentType == null || !contentType.toLowerCase().contains("pdf")) {
            return null;
        }
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** True only when a document's own month is known and differs from the month it was filed under. */
    private static boolean outsidePeriod(LocalDate ownMonth, LocalDate filedMonth) {
        return ownMonth != null && !ownMonth.equals(filedMonth.withDayOfMonth(1));
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    private static String ym(LocalDate d) {
        return d == null ? "?" : "%04d-%02d".formatted(d.getYear(), d.getMonthValue());
    }
}
