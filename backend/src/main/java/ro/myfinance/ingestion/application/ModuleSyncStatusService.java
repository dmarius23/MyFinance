package ro.myfinance.ingestion.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.access.application.UserDirectory;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.ingestion.adapter.persistence.ModuleSyncStatusRepository;
import ro.myfinance.ingestion.domain.ModuleSyncStatus;

/**
 * MOD-15 — records and reads the per (module + month) Drive-sync state. Start/finish are written in their
 * OWN transaction ({@code REQUIRES_NEW}, and called through the proxy from another bean) so the "running"
 * flag commits immediately — before the long sync finishes — making an in-progress sync visible to every
 * user of the tenant, not just the one who triggered it.
 */
@Service
public class ModuleSyncStatusService {

    private final ModuleSyncStatusRepository repo;
    private final UserDirectory users;

    public ModuleSyncStatusService(ModuleSyncStatusRepository repo, UserDirectory users) {
        this.repo = repo;
        this.users = users;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStart(String module, LocalDate period, UUID startedBy) {
        UUID tenantId = TenantContext.tenantId().orElseThrow();
        ModuleSyncStatus s = repo.findByModuleAndPeriodMonth(module, period)
                .orElseGet(() -> new ModuleSyncStatus(tenantId, module, period));
        s.setRunning(true);
        s.setStartedAt(Instant.now());
        s.setStartedBy(startedBy);
        repo.save(s);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFinish(String module, LocalDate period, String result) {
        repo.findByModuleAndPeriodMonth(module, period).ifPresent(s -> {
            s.setRunning(false);
            s.setLastSyncedAt(Instant.now());
            s.setLastResult(result);
            repo.save(s);
        });
    }

    @Transactional(readOnly = true)
    public View get(String module, LocalDate period) {
        return repo.findByModuleAndPeriodMonth(module, period)
                .map(s -> new View(s.isRunning(), s.getStartedAt(), startedByName(s.getStartedBy()),
                        s.getLastSyncedAt(), s.getLastResult()))
                .orElse(new View(false, null, null, null, null));
    }

    private String startedByName(UUID userId) {
        if (userId == null) {
            return null;
        }
        return users.findById(userId)
                .map(u -> u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getEmail())
                .orElse(null);
    }

    /** Read view: whether a sync is running (and who started it, when) plus the last completed sync. */
    public record View(boolean running, Instant startedAt, String startedBy, Instant lastSyncedAt,
                       String lastResult) {
    }
}
