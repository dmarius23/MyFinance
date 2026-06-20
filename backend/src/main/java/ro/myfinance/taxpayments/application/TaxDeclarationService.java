package ro.myfinance.taxpayments.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.taxpayments.adapter.persistence.TaxDeclarationRepository;
import ro.myfinance.taxpayments.domain.DeclarationDetail;
import ro.myfinance.taxpayments.domain.DeclarationView;
import ro.myfinance.taxpayments.domain.TaxDeclaration;

/** Manage uploaded declarations for the declarations modal: list (with flags) and delete. RLS-scoped. */
@Service
@Transactional
public class TaxDeclarationService {

    private final TaxDeclarationRepository declarations;
    private final DocumentService documents;
    private final AnafDeclarationExtractor extractor;

    public TaxDeclarationService(TaxDeclarationRepository declarations, DocumentService documents,
                                 AnafDeclarationExtractor extractor) {
        this.declarations = declarations;
        this.documents = documents;
        this.extractor = extractor;
    }

    @Transactional(readOnly = true)
    public List<DeclarationView> list(UUID companyId, LocalDate period) {
        return declarations.findByCompanyIdAndPeriodMonthOrderByTypeAsc(companyId, period.withDayOfMonth(1))
                .stream().map(DeclarationView::from).toList();
    }

    /** Parsed content of one declaration for the structured preview (XFA PDFs don't render in-browser). */
    @Transactional(readOnly = true)
    public DeclarationDetail detail(UUID companyId, UUID declarationId) {
        TaxDeclaration d = declarations.findById(declarationId)
                .filter(x -> x.getCompanyId().equals(companyId))
                .orElseThrow(() -> new NotFoundException("Declaration not found: " + declarationId));
        return DeclarationDetail.from(extractor.extract(documents.getContent(d.getDocumentId()).bytes()));
    }

    /** Delete the stored declaration and its underlying document. */
    public void delete(UUID companyId, UUID declarationId) {
        TaxDeclaration d = declarations.findById(declarationId)
                .filter(x -> x.getCompanyId().equals(companyId))
                .orElseThrow(() -> new NotFoundException("Declaration not found: " + declarationId));
        UUID documentId = d.getDocumentId();
        declarations.delete(d);
        documents.delete(documentId);
    }
}
