package ro.myfinance.extraction.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ro.myfinance.intake.application.DocumentUploadedEvent;
import ro.myfinance.intake.domain.DocumentType;

/** After a document upload COMMITS, extract + match in its own transaction. Failures never break upload. */
@Component
public class StatementExtractionListener {

    private static final Logger log = LoggerFactory.getLogger(StatementExtractionListener.class);

    private final BankStatementExtractionService statements;
    private final InvoiceExtractionService invoices;

    public StatementExtractionListener(BankStatementExtractionService statements,
                                       InvoiceExtractionService invoices) {
        this.statements = statements;
        this.invoices = invoices;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent e) {
        try {
            if (e.type() == DocumentType.BANK_STATEMENT) {
                statements.extract(e.documentId(), e.companyId(), e.periodMonth(), e.bytes());
            } else if (e.type() == DocumentType.INVOICE) {
                invoices.process(e.documentId(), e.companyId(), e.periodMonth(), e.filename(), e.bytes());
            }
        } catch (RuntimeException ex) {
            log.warn("Extraction failed for document {} ({})", e.documentId(), e.type(), ex);
        }
    }
}
