package ro.myfinance.intake.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.intake.adapter.persistence.DocumentRepository;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Per-document advisory flags for the upload-manager modal (payroll / reports): whether a PDF appears to
 * belong to a different company (CUI not found) and whether its own period differs from the slot it was
 * uploaded into ("outside period"). Deterministic, read-only, best-effort: a flag is {@code null} when it
 * can't be determined (e.g. a scanned PDF with no text, or no detectable date).
 */
@Service
@Transactional(readOnly = true)
public class DocumentFlagService {

    private static final Logger log = LoggerFactory.getLogger(DocumentFlagService.class);

    /** Explicit period line, e.g. "01.03.2026 -- 31.03.2026" (balanță, situație). */
    private static final Pattern PERIOD_RANGE = Pattern.compile(
            "\\d{2}\\.(\\d{2})\\.(\\d{4})\\s*--\\s*\\d{2}\\.\\d{2}\\.\\d{4}");
    /** A single date dd.mm.yyyy. */
    private static final Pattern SINGLE_DATE = Pattern.compile("\\b\\d{2}\\.(\\d{2})\\.(\\d{4})\\b");
    /** A YYYY_MM / YYYY-MM token in the filename. */
    private static final Pattern FILENAME_YM = Pattern.compile("(20\\d{2})[._\\- ](0[1-9]|1[0-2])");

    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final CompanyRepository companies;

    public DocumentFlagService(DocumentRepository documents, DocumentStorage storage, CompanyRepository companies) {
        this.documents = documents;
        this.storage = storage;
        this.companies = companies;
    }

    public record Flags(UUID documentId, Boolean wrongParty, Boolean outsidePeriod, LocalDate detectedPeriod) {
    }

    public List<Flags> flagsFor(UUID companyId, LocalDate periodMonth, DocumentType type) {
        LocalDate month = periodMonth.withDayOfMonth(1);
        Company company = companies.findById(companyId).orElse(null);
        String cui = company == null ? null : company.getCui();
        String name = company == null ? null : company.getLegalName();
        List<Flags> out = new ArrayList<>();
        for (Document d : documents.findByCompanyIdAndPeriodMonthOrderByUploadedAtDesc(companyId, month)) {
            if (d.getType() != type) {
                continue;
            }
            String text = pdfText(d);
            // Belongs to the company if its CUI or name is present; null = can't verify.
            Boolean present = CompanyMatcher.present(text, cui, name);
            Boolean wrongParty = present == null ? null : !present;
            LocalDate docMonth = detectPeriod(text, d.getOriginalFilename());
            Boolean outsidePeriod = docMonth == null ? null : !docMonth.equals(month);
            out.add(new Flags(d.getId(), wrongParty, outsidePeriod, docMonth));
        }
        return out;
    }

    private String pdfText(Document d) {
        if (d.getContentType() == null || !d.getContentType().toLowerCase().contains("pdf")) {
            return null;
        }
        try (PDDocument pdf = Loader.loadPDF(storage.retrieve(d.getStorageKey()))) {
            String t = new PDFTextStripper().getText(pdf);
            return t == null || t.isBlank() ? null : t;
        } catch (Exception e) {
            // Best-effort: an unreadable/scanned PDF just yields no flags. Log so it's diagnosable.
            log.warn("Could not extract text for flags on document {} ({})", d.getId(), d.getOriginalFilename(), e);
            return null;
        }
    }

    /** First-of-month for the document's own period — from a period range, the filename, or a single date. */
    private static LocalDate detectPeriod(String text, String filename) {
        if (text != null) {
            Matcher r = PERIOD_RANGE.matcher(text);
            if (r.find()) {
                return LocalDate.of(Integer.parseInt(r.group(2)), Integer.parseInt(r.group(1)), 1);
            }
        }
        if (filename != null) {
            Matcher f = FILENAME_YM.matcher(filename);
            if (f.find()) {
                return LocalDate.of(Integer.parseInt(f.group(1)), Integer.parseInt(f.group(2)), 1);
            }
        }
        if (text != null) {
            Matcher s = SINGLE_DATE.matcher(text);
            if (s.find()) {
                return LocalDate.of(Integer.parseInt(s.group(2)), Integer.parseInt(s.group(1)), 1);
            }
        }
        return null;
    }
}
