package ro.myfinance.taxpayments.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
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
    public void onDocumentUploaded(DocumentUploadedEvent e) {
        try {
            if (e.type() != DocumentType.DECLARATION) {
                declarations.deleteByDocumentId(e.documentId()); // no-op unless it was a declaration before
                return;
            }
            ParsedDeclaration pd = extractor.extract(e.bytes());
            String ownCui = companies.findById(e.companyId()).map(Company::getCui).orElse(null);
            boolean wrongParty = differentCui(pd.cui(), ownCui);
            LocalDate declPeriod = pd.period() == null ? null : pd.period().atDay(1);
            TaxDeclaration row = declarations.findByDocumentId(e.documentId())
                    .orElseGet(() -> new TaxDeclaration(TenantContext.tenantId().orElseThrow(),
                            e.companyId(), e.periodMonth().withDayOfMonth(1), e.documentId()));
            row.apply(pd.type(), pd.cui(), pd.declaredTotal(), pd.computedTotal(), pd.totalsMismatch(),
                    declPeriod, wrongParty);
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
