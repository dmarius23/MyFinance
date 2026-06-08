package ro.myfinance.extraction.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ro.myfinance.intake.application.DocumentUploadedEvent;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Extracts bank statements after a document upload COMMITS — in its own transaction. Running
 * after-commit (not inline) means a parse/persistence failure can never roll back the user's
 * upload or orphan the stored file; failures are logged and the statement simply isn't created.
 */
@Component
public class StatementExtractionListener {

    private static final Logger log = LoggerFactory.getLogger(StatementExtractionListener.class);

    private final BankStatementExtractionService service;

    public StatementExtractionListener(BankStatementExtractionService service) {
        this.service = service;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent e) {
        if (e.type() != DocumentType.BANK_STATEMENT) {
            return;
        }
        try {
            service.extract(e.documentId(), e.companyId(), e.periodMonth(), e.bytes());
        } catch (RuntimeException ex) {
            log.warn("Bank-statement extraction failed for document {}", e.documentId(), ex);
        }
    }
}
