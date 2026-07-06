package ro.myfinance.extraction.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ro.myfinance.extraction.adapter.persistence.BankStatementRepository;
import ro.myfinance.extraction.adapter.persistence.InvoiceRepository;
import ro.myfinance.intake.application.DocumentDeletedEvent;
import ro.myfinance.intake.application.DocumentUploadedEvent;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Extracts + matches when a document is uploaded (or re-uploaded / re-classified / its type changed).
 * Fires synchronously on the publish, within the caller's transaction (so the document is visible for
 * the bank_statement FK). First purges any prior extraction artifacts for the document, making the
 * operation idempotent and safe across type changes. Failures are caught and logged so a parse
 * problem never propagates out of the upload. (A future async worker/queue is the intended decoupling.)
 */
@Component
public class StatementExtractionListener {

    private static final Logger log = LoggerFactory.getLogger(StatementExtractionListener.class);

    private final BankStatementExtractionService statements;
    private final InvoiceExtractionService invoices;
    private final BankStatementRepository statementRepo;
    private final InvoiceRepository invoiceRepo;

    public StatementExtractionListener(BankStatementExtractionService statements,
                                       InvoiceExtractionService invoices,
                                       BankStatementRepository statementRepo,
                                       InvoiceRepository invoiceRepo) {
        this.statements = statements;
        this.invoices = invoices;
        this.statementRepo = statementRepo;
        this.invoiceRepo = invoiceRepo;
    }

    @EventListener
    public void onDocumentDeleted(DocumentDeletedEvent e) {
        // Clean up all extraction artifacts for the deleted document.
        invoiceRepo.deleteByDocumentId(e.documentId());
        statementRepo.deleteByDocumentId(e.documentId());
    }

    @EventListener
    public void onDocumentUploaded(DocumentUploadedEvent e) {
        try {
            if (e.type() == DocumentType.BANK_STATEMENT) {
                // Cross-type cleanup only (no-op unless this doc was previously an invoice).
                invoiceRepo.deleteByDocumentId(e.documentId());
                // extract() is idempotent per document: a statement already extracted successfully is
                // left as-is (its manual matches preserved), while a prior failed/empty parse is
                // replaced — so a re-scan can populate it once a matching bank parser exists.
                statements.extract(e.documentId(), e.companyId(), e.periodMonth(), e.bytes());
            } else if (e.type() == DocumentType.INVOICE || e.type() == DocumentType.RECEIPT) {
                // Cross-type cleanup only (no-op unless this doc was previously a statement).
                statementRepo.deleteByDocumentId(e.documentId());
                // Receipts are supporting documents too — extract them as invoices so they become
                // matchable/linkable. Image-only receipts parse to null fields (OCR is future work);
                // they still appear in the manual link picker by filename. process() upserts, so a
                // re-scan refreshes fields in place and preserves the row's existing matches.
                invoices.process(e.documentId(), e.companyId(), e.periodMonth(), e.filename(), e.bytes());
            }
        } catch (RuntimeException ex) {
            log.warn("Extraction failed for document {} ({})", e.documentId(), e.type(), ex);
        }
    }
}
