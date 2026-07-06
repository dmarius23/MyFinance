package ro.myfinance.reports.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The stored monthly report for a company (table {@code trial_balance}): the source document plus the
 * computed {@link ReportData} serialized as JSON. One row per company/period — a re-upload replaces the
 * snapshot and bumps {@link #version}.
 */
@Entity
@Table(name = "trial_balance")
public class ReportSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(nullable = false)
    private int version = 1;

    @Column(nullable = false)
    private boolean balanced;

    @Column(name = "report_json", nullable = false)
    private String reportJson;

    /**
     * The period extracted from the trial-balance PDF itself (the printed date range).
     * Null for snapshots created before this column existed — detected and cached lazily on first read.
     * When non-null and different from {@link #periodMonth}, this snapshot must not be served.
     */
    @Column(name = "content_period")
    private LocalDate contentPeriod;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ReportSnapshot() {
    }

    public ReportSnapshot(UUID tenantId, UUID companyId, LocalDate periodMonth, UUID documentId,
                          boolean balanced, String reportJson, LocalDate contentPeriod) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.periodMonth = periodMonth;
        this.documentId = documentId;
        this.balanced = balanced;
        this.reportJson = reportJson;
        this.contentPeriod = contentPeriod;
    }

    /** Replace the report content on re-upload, bumping the version. */
    public void replace(UUID documentId, boolean balanced, String reportJson, LocalDate contentPeriod) {
        this.documentId = documentId;
        this.balanced = balanced;
        this.reportJson = reportJson;
        this.contentPeriod = contentPeriod;
        this.version += 1;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public LocalDate getPeriodMonth() { return periodMonth; }
    public UUID getDocumentId() { return documentId; }
    public int getVersion() { return version; }
    public boolean isBalanced() { return balanced; }
    public String getReportJson() { return reportJson; }
    public LocalDate getContentPeriod() { return contentPeriod; }
    public void setContentPeriod(LocalDate contentPeriod) { this.contentPeriod = contentPeriod; this.updatedAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
