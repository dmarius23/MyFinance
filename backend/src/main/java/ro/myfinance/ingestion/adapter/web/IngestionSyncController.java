package ro.myfinance.ingestion.adapter.web;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.ingestion.adapter.web.IngestionDtos.SourceStatus;
import ro.myfinance.ingestion.adapter.web.IngestionDtos.SyncCompanyRequest;
import ro.myfinance.ingestion.adapter.web.IngestionDtos.SyncResponse;
import ro.myfinance.ingestion.application.IngestionService;

/**
 * MOD-15 — staff-facing ingestion: ask whether a document type is Drive-sourced (so the UI hides manual
 * upload/delete) and run a scoped sync for one company + month. Open to admins and employees who manage
 * documents (configuring the connection itself stays admin-only on {@link IngestionController}).
 */
@RestController
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class IngestionSyncController {

    private final IngestionService ingestion;

    public IngestionSyncController(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @GetMapping("/api/v1/ingestion/source")
    public SourceStatus source(@RequestParam("type") String type) {
        var s = ingestion.driveStatusFor(type);
        return new SourceStatus(s.enabled(), s.write());
    }

    @PostMapping("/api/v1/ingestion/sync-company")
    public SyncResponse syncCompany(@Valid @RequestBody SyncCompanyRequest req) {
        return SyncResponse.from(ingestion.syncCompanyMonth(req.type(), req.companyId(), req.period()));
    }
}
