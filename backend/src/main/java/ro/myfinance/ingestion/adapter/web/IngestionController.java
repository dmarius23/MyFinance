package ro.myfinance.ingestion.adapter.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.ingestion.adapter.web.IngestionDtos.ConnectionView;
import ro.myfinance.ingestion.adapter.web.IngestionDtos.CreateConnectionRequest;
import ro.myfinance.ingestion.adapter.web.IngestionDtos.ImportView;
import ro.myfinance.ingestion.adapter.web.IngestionDtos.SyncResponse;
import ro.myfinance.ingestion.adapter.web.IngestionDtos.UpdateConnectionRequest;
import ro.myfinance.ingestion.application.IngestionService;

/**
 * MOD-15 — administrators configure the document source folders and trigger a sync. Firm admins only;
 * the Drive location and per-company layout are set here, never by representatives.
 */
@RestController
@RequestMapping("/api/v1/ingestion/connections")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class IngestionController {

    private final IngestionService ingestion;

    public IngestionController(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @GetMapping
    public List<ConnectionView> list() {
        return ingestion.list().stream().map(ConnectionView::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionView create(@Valid @RequestBody CreateConnectionRequest req) {
        return ConnectionView.from(ingestion.create(req.provider(), req.displayName(), req.rootFolderId(),
                req.forcedType(), req.config()));
    }

    @PutMapping("/{id}")
    public ConnectionView update(@PathVariable UUID id, @RequestBody UpdateConnectionRequest req) {
        return ConnectionView.from(ingestion.update(id, req.displayName(), req.rootFolderId(),
                req.forcedType(), req.config(), req.status()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        ingestion.delete(id);
    }

    @PostMapping("/{id}/sync")
    public SyncResponse sync(@PathVariable UUID id) {
        return SyncResponse.from(ingestion.sync(id));
    }

    @GetMapping("/{id}/imports")
    public List<ImportView> imports(@PathVariable UUID id) {
        return ingestion.imports(id).stream().map(ImportView::from).toList();
    }
}
