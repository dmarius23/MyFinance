package ro.myfinance.reports.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ro.myfinance.intake.application.DocumentUploadedEvent;
import ro.myfinance.intake.domain.DocumentType;
import ro.myfinance.reports.adapter.persistence.ReportSnapshotRepository;

/**
 * On every TRIAL_BALANCE upload (or re-upload / reclassify), extract + compute + store the monthly
 * report. Idempotent per company/period (re-upload bumps the version). If a document stops being a
 * trial balance, its snapshot is removed. Failures never break the upload.
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

    @EventListener
    public void onDocumentUploaded(DocumentUploadedEvent e) {
        try {
            if (e.type() != DocumentType.TRIAL_BALANCE) {
                snapshots.deleteByDocumentId(e.documentId()); // no-op unless it was a trial balance before
                return;
            }
            reports.ingest(e.companyId(), e.periodMonth(), e.documentId(), e.bytes());
        } catch (RuntimeException ex) {
            log.warn("Failed to build report for document {}", e.documentId(), ex);
        }
    }
}
