package ro.myfinance.settings.adapter.web;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.settings.adapter.web.AnafSyncDtos.StartSyncRequest;
import ro.myfinance.settings.adapter.web.AnafSyncDtos.SyncItemResponse;
import ro.myfinance.settings.adapter.web.AnafSyncDtos.SyncRunResponse;
import ro.myfinance.settings.application.AnafTreasurySyncService;
import ro.myfinance.settings.domain.SyncChange;

/**
 * SUPER_ADMIN control of the ANAF treasury-IBAN sync. Start a run (crawls off-request), review its diff,
 * then apply or cancel it. Under {@code /api/v1/admin/**}, which {@code SecurityConfig} restricts to
 * SUPER_ADMIN; the {@code @PreAuthorize} restates that intent.
 */
@RestController
@RequestMapping("/api/v1/admin/treasury-sync")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AnafTreasurySyncController {

    private final AnafTreasurySyncService sync;

    public AnafTreasurySyncController(AnafTreasurySyncService sync) {
        this.sync = sync;
    }

    /** Start a sync; returns 202 with the RUNNING run (poll {@code GET /{runId}} for progress). */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SyncRunResponse start(@RequestBody(required = false) StartSyncRequest req) {
        UUID startedBy = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        return SyncRunResponse.from(sync.startSync(startedBy, req == null ? null : req.effectiveFrom()));
    }

    @GetMapping
    public List<SyncRunResponse> list() {
        return sync.listRuns().stream().map(SyncRunResponse::from).toList();
    }

    @GetMapping("/{runId}")
    public SyncRunResponse get(@PathVariable UUID runId) {
        return SyncRunResponse.from(sync.get(runId));
    }

    /** The diff rows; optionally filter with {@code ?change=ADDED,CHANGED}. */
    @GetMapping("/{runId}/items")
    public List<SyncItemResponse> items(@PathVariable UUID runId,
                                        @RequestParam(required = false) List<SyncChange> change) {
        return sync.itemsFor(runId, change).stream().map(SyncItemResponse::from).toList();
    }

    @PostMapping("/{runId}/apply")
    public SyncRunResponse apply(@PathVariable UUID runId) {
        return SyncRunResponse.from(sync.apply(runId));
    }

    @PostMapping("/{runId}/cancel")
    public SyncRunResponse cancel(@PathVariable UUID runId) {
        return SyncRunResponse.from(sync.cancel(runId));
    }
}
