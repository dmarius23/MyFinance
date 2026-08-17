package ro.myfinance.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * MOD-15 — the Drive-sync state for one (module + month), shared across the tenant: when it last ran, its
 * result, and whether a sync is running right now (so an in-progress sync is visible to every user).
 * Tenant-scoped via RLS. One row per (tenant, module, period).
 */
@Entity
@Table(name = "module_sync_status")
public class ModuleSyncStatus {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String module;

    @Column(name = "period_month", nullable = false, updatable = false)
    private LocalDate periodMonth;

    @Column(nullable = false)
    private boolean running;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "started_by")
    private UUID startedBy;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_result")
    private String lastResult;

    protected ModuleSyncStatus() {
    }

    public ModuleSyncStatus(UUID tenantId, String module, LocalDate periodMonth) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.module = module;
        this.periodMonth = periodMonth;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getModule() { return module; }
    public LocalDate getPeriodMonth() { return periodMonth; }
    public boolean isRunning() { return running; }
    public void setRunning(boolean v) { this.running = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public UUID getStartedBy() { return startedBy; }
    public void setStartedBy(UUID v) { this.startedBy = v; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant v) { this.lastSyncedAt = v; }
    public String getLastResult() { return lastResult; }
    public void setLastResult(String v) { this.lastResult = v; }
}
