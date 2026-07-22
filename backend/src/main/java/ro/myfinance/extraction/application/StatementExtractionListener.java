package ro.myfinance.extraction.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ro.myfinance.common.async.AsyncConfig;
import ro.myfinance.extraction.adapter.persistence.BankStatementRepository;
import ro.myfinance.extraction.adapter.persistence.InvoiceRepository;
import ro.myfinance.intake.application.DocumentDeletedEvent;
import ro.myfinance.intake.application.DocumentUploadedEvent;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Extracts + matches when a document is uploaded (or re-uploaded / re-classified / its type changed).
 * Runs <b>after the upload commits</b> and <b>off the request thread</b> ({@code AFTER_COMMIT} +
 * {@code @Async}), each invocation in its own transaction — so the HTTP upload returns immediately and
 * the sibling pipeline listeners no longer contend on the same row within one transaction. First purges
 * any prior extraction artifacts for the document, making the operation idempotent and safe across type
 * changes. Failures are caught and logged so a parse problem never surfaces to the user.
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

    @Async(AsyncConfig.DOCUMENT_PIPELINE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDocumentDeleted(DocumentDeletedEvent e) {
        // Clean up all extraction artifacts for the deleted document.
        invoiceRepo.deleteByDocumentId(e.documentId());
        statementRepo.deleteByDocumentId(e.documentId());
    }

    @Async(AsyncConfig.DOCUMENT_PIPELINE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
            } else {
                // Any other type (unclassified, payroll, declaration, trial balance): the document is now
                // neither an invoice nor a bank statement, so purge extraction artifacts left over from a
                // previous type — e.g. a stale NEEDS_REVIEW statement created when it was mis-classified
                // as BANK_STATEMENT before being reclassified.
                statementRepo.deleteByDocumentId(e.documentId());
                invoiceRepo.deleteByDocumentId(e.documentId());
            }
        } catch (RuntimeException ex) {
            log.warn("Extraction failed for document {} ({})", e.documentId(), e.type(), ex);
        }
    }
}
