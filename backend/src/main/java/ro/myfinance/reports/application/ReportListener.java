package ro.myfinance.reports.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ro.myfinance.common.async.AsyncConfig;
import ro.myfinance.intake.application.DocumentDeletedEvent;
import ro.myfinance.intake.application.DocumentUploadedEvent;
import ro.myfinance.intake.domain.DocumentType;
import ro.myfinance.reports.adapter.persistence.ReportSnapshotRepository;

/**
 * On every TRIAL_BALANCE upload (or re-upload / reclassify), extract + compute + store the monthly
 * report. Runs after the upload commits and off the request thread ({@code AFTER_COMMIT} + {@code @Async}),
 * in its own transaction. Idempotent per company/period (re-upload bumps the version). If a document stops
 * being a trial balance, its snapshot is removed. Failures never break the upload.
 */
@Component
public class ReportListener {

    private static final Logger log = LoggerFactory.getLogger(ReportListener.class);

    private final ReportService reports;
    private final ReportSnapshotRepository snapshots;

    public ReportListener(ReportService reports, ReportSnapshotRepository snapshots) {
        this.reports = reports;
        this.snapshots = snapshots;
    }

    @Async(AsyncConfig.DOCUMENT_PIPELINE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDocumentDeleted(DocumentDeletedEvent e) {
        snapshots.deleteByDocumentId(e.documentId());
    }

    @Async(AsyncConfig.DOCUMENT_PIPELINE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDocumentUploaded(DocumentUploadedEvent e) {
        try {
            // Always purge the stale snapshot for this document first — covers type changes, re-uploads,
            // and period moves. The ingest step creates a fresh one when the period matches.
            snapshots.deleteByDocumentId(e.documentId());
            if (e.type() != DocumentType.TRIAL_BALANCE) {
                return;
            }
            reports.ingest(e.companyId(), e.periodMonth(), e.documentId(), e.bytes());
        } catch (RuntimeException ex) {
            log.warn("Failed to build report for document {}", e.documentId(), ex);
        }
    }
}
