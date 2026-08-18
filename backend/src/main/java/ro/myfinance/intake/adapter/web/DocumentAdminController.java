package ro.myfinance.intake.adapter.web;

import java.time.LocalDate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Staff-only maintenance: re-fire the document pipeline for a month's documents of a type — rebuilds
 * reports / extractions from already-imported documents (e.g. after ingest logic changed), no re-import.
 */
@RestController
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class DocumentAdminController {

    private final DocumentService service;

    public DocumentAdminController(DocumentService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/documents/reprocess")
    public ReprocessResponse reprocess(@RequestBody ReprocessRequest req) {
        return new ReprocessResponse(service.reprocess(req.period(), req.type()));
    }

    public record ReprocessRequest(LocalDate period, DocumentType type) {
    }

    public record ReprocessResponse(int reprocessed) {
    }
}
