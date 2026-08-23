package ro.myfinance.taxpayments.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.time.LocalDate;
import ro.myfinance.common.async.AsyncConfig;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.application.CompanyDirectory;
import ro.myfinance.company.domain.Company;
import ro.myfinance.intake.application.DocumentDeletedEvent;
import ro.myfinance.intake.application.DocumentUploadedEvent;
import ro.myfinance.intake.domain.DocumentType;
import ro.myfinance.taxpayments.adapter.persistence.TaxDeclarationRepository;
import ro.myfinance.taxpayments.domain.DeclarationType;
import ro.myfinance.taxpayments.domain.ObligationLine;
import ro.myfinance.taxpayments.domain.ParsedDeclaration;
import ro.myfinance.taxpayments.domain.TaxDeclaration;

/**
 * On every DECLARATION upload (or re-upload / reclassify), extract its ANAF XML and store/refresh the
 * tax_declaration summary. Runs after the upload commits and off the request thread ({@code AFTER_COMMIT}
 * + {@code @Async}), in its own transaction. Idempotent: upserts by document id. Cross-type cleanup
 * removes a stale row if the document was previously a declaration and is now something else. Failures
 * never break upload.
 */
@Component
public class TaxDeclarationListener {

    private static final Logger log = LoggerFactory.getLogger(TaxDeclarationListener.class);

    private final AnafDeclarationExtractor extractor;
    private final TaxDeclarationRepository declarations;
    private final CompanyDirectory companies;

    public TaxDeclarationListener(AnafDeclarationExtractor extractor, TaxDeclarationRepository declarations,
                                  CompanyDirectory companies) {
        this.extractor = extractor;
        this.declarations = declarations;
        this.companies = companies;
    }

    @Async(AsyncConfig.DOCUMENT_PIPELINE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDocumentDeleted(DocumentDeletedEvent e) {
        declarations.deleteByDocumentId(e.documentId());
    }

    @Async(AsyncConfig.DOCUMENT_PIPELINE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
            // Persist the itemized obligations (code + amount) so the list can show one line per creanță.
            // A declaration whose own period (from the ANAF XML) belongs to a different month is still stored,
            // flagged outside-period ({@link TaxDeclaration#isOutsidePeriod()}) with a "Move to correct
            // period" action, and excluded from this month's payment totals.
            java.util.List<ObligationLine> obligations = pd.obligations().stream()
                    .map(o -> new ObligationLine(o.codOblig(), o.amount()))
                    .toList();
            TaxDeclaration row = new TaxDeclaration(TenantContext.tenantId().orElseThrow(),
                    e.companyId(), storedMonth, e.documentId());
            row.apply(pd.type(), pd.cui(), pd.declaredTotal(), pd.computedTotal(), pd.totalsMismatch(),
                    declPeriod, wrongParty, false, obligations);

            // Auto-dedup: keep ONE declaration per (company, form, month) — except D100, which a company may
            // legitimately file once per fiscal obligation (creanță 103 profit / 604 dividende / 618 chirii …),
            // so D100s are keyed by their obligation code(s). A re-imported or regenerated copy arrives as a
            // *different* document filling the same slot; it supersedes the older one (removed here) instead
            // of piling up a duplicate.
            String slot = obligationSlot(pd.type(), obligations);
            declarations.findByCompanyIdAndPeriodMonthOrderByTypeAsc(e.companyId(), storedMonth).stream()
                    .filter(prev -> prev.getType() == pd.type()
                            && !e.documentId().equals(prev.getDocumentId())
                            && obligationSlot(prev.getType(), prev.getObligations()).equals(slot))
                    .forEach(declarations::delete);
            declarations.save(row);
        } catch (RuntimeException ex) {
            log.warn("Failed to store tax declaration for document {}", e.documentId(), ex);
        }
    }

    /** The dedup slot a declaration occupies: one per form per month — but D100 is one per fiscal obligation
     *  code (creanță), since a company can file several D100s (chirii / dividende / profit) in the same month. */
    private static String obligationSlot(DeclarationType type, java.util.List<ObligationLine> obligations) {
        if (type != DeclarationType.D100) {
            return "";
        }
        return obligations.stream().map(ObligationLine::cod)
                .filter(java.util.Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                .sorted().distinct().collect(java.util.stream.Collectors.joining(","));
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
