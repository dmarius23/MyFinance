package ro.myfinance.extraction.application;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ro.myfinance.intake.application.DocumentUploadedEvent;
import ro.myfinance.intake.domain.DocumentType;

/** Synchronously extracts bank statements when a document is uploaded. */
@Component
public class StatementExtractionListener {

    private final BankStatementExtractionService service;

    public StatementExtractionListener(BankStatementExtractionService service) {
        this.service = service;
    }

    @EventListener
    public void onDocumentUploaded(DocumentUploadedEvent e) {
        if (e.type() == DocumentType.BANK_STATEMENT) {
            service.extract(e.documentId(), e.companyId(), e.periodMonth(), e.bytes());
        }
    }
}
