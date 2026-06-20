package ro.myfinance.taxpayments.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.taxpayments.adapter.persistence.TaxDeclarationRepository;
import ro.myfinance.taxpayments.domain.DeclarationView;
import ro.myfinance.taxpayments.domain.TaxDeclaration;

/** Manage uploaded declarations for the declarations modal: list (with flags) and delete. RLS-scoped. */
@Service
@Transactional
public class TaxDeclarationService {

    private final TaxDeclarationRepository declarations;
    private final DocumentService documents;

    public TaxDeclarationService(TaxDeclarationRepository declarations, DocumentService documents) {
        this.declarations = declarations;
        this.documents = documents;
    }

    @Transactional(readOnly = true)
    public List<DeclarationView> list(UUID companyId, LocalDate period) {
        return declarations.findByCompanyIdAndPeriodMonthOrderByTypeAsc(companyId, period.withDayOfMonth(1))
                .stream().map(DeclarationView::from).toList();
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
