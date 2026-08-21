package ro.myfinance.ingestion.adapter.persistence;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ro.myfinance.ingestion.domain.ModuleSyncStatus;

public interface ModuleSyncStatusRepository extends JpaRepository<ModuleSyncStatus, UUID> {

    Optional<ModuleSyncStatus> findByModuleAndPeriodMonth(String module, LocalDate periodMonth);

    /**
     * Atomically claim the (module, month) sync slot: insert a running row, or take over an existing one
     * only if it is idle or its running flag is stale (a crashed sync left it set). Returns 1 when the slot
     * was claimed, 0 when a fresh sync is already running — the unique-index arbiter serialises concurrent
     * callers so a second sync of the same module/month is rejected.
     */
    @Modifying
    @Query(value = """
            INSERT INTO module_sync_status (id, tenant_id, module, period_month, running, started_at, started_by)
            VALUES (gen_random_uuid(), :tenantId, :module, :period, true, now(), :startedBy)
            ON CONFLICT (tenant_id, module, period_month) DO UPDATE
               SET running = true, started_at = now(), started_by = :startedBy
               WHERE module_sync_status.running = false
                  OR module_sync_status.started_at < now() - make_interval(mins => :staleMinutes)
            """, nativeQuery = true)
    int tryAcquire(@Param("tenantId") UUID tenantId, @Param("module") String module,
                   @Param("period") LocalDate period, @Param("startedBy") UUID startedBy,
                   @Param("staleMinutes") int staleMinutes);
}
