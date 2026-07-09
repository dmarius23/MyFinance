package ro.myfinance.taxpayments.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.intake.application.DocumentDeletedEvent;
import ro.myfinance.intake.application.DocumentUploadedEvent;
import ro.myfinance.intake.domain.DocumentType;
import ro.myfinance.taxpayments.adapter.persistence.TaxDeclarationRepository;
import ro.myfinance.taxpayments.domain.ParsedDeclaration;
import ro.myfinance.taxpayments.domain.TaxDeclaration;

/**
 * On every DECLARATION upload (or re-upload / reclassify), extract its ANAF XML and store/refresh the
 * tax_declaration summary. Idempotent: upserts by document id. Cross-type cleanup removes a stale row
 * if the document was previously a declaration and is now something else. Failures never break upload.
 */
@Component
public class TaxDeclarationListener {

    private static final Logger log = LoggerFactory.getLogger(TaxDeclarationListener.class);

    private final AnafDeclarationExtractor extractor;
    private final TaxDeclarationRepository declarations;
    private final CompanyRepository companies;

    public TaxDeclarationListener(AnafDeclarationExtractor extractor, TaxDeclarationRepository declarations,
                                  CompanyRepository companies) {
        this.extractor = extractor;
        this.declarations = declarations;
        this.companies = companies;
    }

    @EventListener
    public void onDocumentDeleted(DocumentDeletedEvent e) {
        declarations.deleteByDocumentId(e.documentId());
    }

    @EventListener
    public void onDocumentUploaded(DocumentUploadedEvent e) {
        try {
            // Always purge stale declaration row first — covers type changes, re-uploads, period moves.
            declarations.deleteByDocumentId(e.documentId());
            if (e.type() != DocumentType.DECLARATION) {
                return;
            }
            ParsedDeclaration pd = extractor.extract(e.bytes());
            String ownCui = companies.findById(e.companyId()).map(Company::getCui).orElse(null);
            boolean wrongParty = differentCui(pd.cui(), ownCui);
            LocalDate declPeriod = pd.period() == null ? null : pd.period().atDay(1);
            LocalDate storedMonth = e.periodMonth().withDayOfMonth(1);
            // A declaration whose own period (from the ANAF XML) belongs to a different month is stored
            // flagged outside-period ({@link TaxDeclaration#isOutsidePeriod()} is derived from declPeriod
            // vs the slot). It shows in the manager with a "Move to correct period" action and is excluded
            // from this month's payment totals — but is kept so the accountant can see and fix it.
            //
            // Duplicate handling: the copy filed in its OWN month (period_month == declPeriod) is the
            // canonical one; a copy uploaded into the wrong month is the duplicate — never the other way
            // round. So the same declaration dropped into both the right and a wrong month still shows,
            // and stays selectable, under its correct month.
            boolean inPeriod = declPeriod != null && declPeriod.equals(storedMonth);
            java.util.List<TaxDeclaration> copies = declPeriod == null ? java.util.List.of()
                    : declarations.findByCompanyIdAndTypeAndDeclPeriod(e.companyId(), pd.type(), declPeriod).stream()
                            .filter(s -> !e.documentId().equals(s.getDocumentId())).toList();
            boolean duplicate;
            if (inPeriod) {
                // Canonical unless another copy is ALSO correctly filed in this month; a mis-filed copy is
                // demoted to duplicate below instead of suppressing this one.
                duplicate = copies.stream().anyMatch(s -> declPeriod.equals(s.getPeriodMonth()) && !s.isDuplicate());
                if (!duplicate) {
                    copies.stream().filter(s -> !declPeriod.equals(s.getPeriodMonth()) && !s.isDuplicate())
                            .forEach(TaxDeclaration::markDuplicate);
                }
            } else {
                // A copy filed in the wrong month is the duplicate whenever any other copy exists.
                duplicate = !copies.isEmpty();
            }
            TaxDeclaration row = new TaxDeclaration(TenantContext.tenantId().orElseThrow(),
                    e.companyId(), storedMonth, e.documentId());
            row.apply(pd.type(), pd.cui(), pd.declaredTotal(), pd.computedTotal(), pd.totalsMismatch(),
                    declPeriod, wrongParty, duplicate);
            declarations.save(row);
        } catch (RuntimeException ex) {
            log.warn("Failed to store tax declaration for document {}", e.documentId(), ex);
        }
    }

    /** Wrong party only when both CUIs are known and their digits differ. */
    private static boolean differentCui(String declCui, String ownCui) {
        String a = digits(declCui);
        String b = digits(ownCui);
        return !a.isEmpty() && !b.isEmpty() && !a.equals(b);
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }
}
