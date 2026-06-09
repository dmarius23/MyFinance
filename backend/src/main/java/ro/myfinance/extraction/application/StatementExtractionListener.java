package ro.myfinance.extraction.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ro.myfinance.intake.application.DocumentUploadedEvent;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Extracts + matches when a document is uploaded. Fires synchronously on the publish (within the
 * upload transaction, so the document is visible for the bank_statement FK). Failures are caught and
 * logged so a parse problem never propagates out of the upload. (A future async worker/queue —
 * MOD job `extract-document` — is the intended decoupling for heavy extraction.)
 */
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

    @EventListener
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
