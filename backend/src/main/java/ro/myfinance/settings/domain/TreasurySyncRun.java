package ro.myfinance.settings.domain;

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
import org.hibernate.annotations.CreationTimestamp;

/**
 * One ANAF treasury-IBAN sync run: a crawl of the catalogue whose per-treasury diff (the
 * {@link TreasurySyncItem}s) is reviewed by a SUPER_ADMIN and then applied to the live treasury table.
 * GLOBAL reference-side data — no {@code tenant_id}/RLS (see V39); writes gated by {@code hasRole('SUPER_ADMIN')}.
 */
@Entity
@Table(name = "platform_treasury_sync_run")
public class TreasurySyncRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncRunStatus status;

    @Column(name = "started_by")
    private UUID startedBy;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "counties_total", nullable = false)
    private int countiesTotal;

    @Column(name = "treasuries_total", nullable = false)
    private int treasuriesTotal;

    @Column(name = "parsed_ok", nullable = false)
    private int parsedOk;

    @Column(name = "parse_failed", nullable = false)
    private int parseFailed;

    @Column
    private String notes;

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    protected TreasurySyncRun() {
    }

    public TreasurySyncRun(UUID startedBy, LocalDate effectiveFrom) {
        this.startedBy = startedBy;
        this.effectiveFrom = effectiveFrom;
        this.status = SyncRunStatus.RUNNING;
    }

    /** Crawl finished: record the tallies and open the run for review. */
    public void markReadyForReview(int countiesTotal, int treasuriesTotal, int parsedOk, int parseFailed) {
        this.countiesTotal = countiesTotal;
        this.treasuriesTotal = treasuriesTotal;
        this.parsedOk = parsedOk;
        this.parseFailed = parseFailed;
        this.status = SyncRunStatus.READY_FOR_REVIEW;
        this.finishedAt = Instant.now();
    }

    public void markApplied() {
        this.status = SyncRunStatus.APPLIED;
        this.appliedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = SyncRunStatus.FAILED;
        this.notes = reason;
        this.finishedAt = Instant.now();
    }

    public void markCancelled() {
        this.status = SyncRunStatus.CANCELLED;
        this.finishedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public SyncRunStatus getStatus() { return status; }
    public UUID getStartedBy() { return startedBy; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public int getCountiesTotal() { return countiesTotal; }
    public int getTreasuriesTotal() { return treasuriesTotal; }
    public int getParsedOk() { return parsedOk; }
    public int getParseFailed() { return parseFailed; }
    public String getNotes() { return notes; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getAppliedAt() { return appliedAt; }
}
