package ro.myfinance.reports.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import ro.myfinance.reports.application.Granularity;

/**
 * A cached aggregated report for a calendar period wider than a month (table {@code period_report}):
 * the {@link ReportData} folded from the monthly {@code trial_balance} snapshots it encloses, serialized
 * as JSON. One row per company/granularity/period. {@link #sourceFingerprint} captures which monthly
 * snapshots (and versions) it was built from — when a month is re-uploaded the fingerprint no longer
 * matches and the row is regenerated. {@code MONTH} is never stored here (served from the snapshot).
 */
@Entity
@Table(name = "period_report")
public class PeriodReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Granularity granularity;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "report_json", nullable = false)
    private String reportJson;

    @Column(nullable = false)
    private boolean complete;

    @Column(name = "months_present", nullable = false)
    private int monthsPresent;

    @Column(name = "months_expected", nullable = false)
    private int monthsExpected;

    @Column(name = "source_fingerprint", nullable = false)
    private String sourceFingerprint;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PeriodReport() {
    }

    public PeriodReport(UUID tenantId, UUID companyId, Granularity granularity, LocalDate periodStart,
                        String reportJson, boolean complete, int monthsPresent, int monthsExpected,
                        String sourceFingerprint) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.granularity = granularity;
        this.periodStart = periodStart;
        this.reportJson = reportJson;
        this.complete = complete;
        this.monthsPresent = monthsPresent;
        this.monthsExpected = monthsExpected;
        this.sourceFingerprint = sourceFingerprint;
    }

    /** Replace the cached content when the constituent months changed (fingerprint no longer matched). */
    public void refresh(String reportJson, boolean complete, int monthsPresent, int monthsExpected,
                        String sourceFingerprint) {
        this.reportJson = reportJson;
        this.complete = complete;
        this.monthsPresent = monthsPresent;
        this.monthsExpected = monthsExpected;
        this.sourceFingerprint = sourceFingerprint;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public Granularity getGranularity() { return granularity; }
    public LocalDate getPeriodStart() { return periodStart; }
    public String getReportJson() { return reportJson; }
    public boolean isComplete() { return complete; }
    public int getMonthsPresent() { return monthsPresent; }
    public int getMonthsExpected() { return monthsExpected; }
    public String getSourceFingerprint() { return sourceFingerprint; }
    public Instant getGeneratedAt() { return generatedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
