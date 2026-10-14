package ro.myfinance.ingestion.adapter.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.ingestion.adapter.web.IngestionDtos.ImportView;
import ro.myfinance.ingestion.application.IngestionService;

/**
 * Drive files that did NOT become documents, scoped to a company + month. Deliberately NOT under
 * {@code /connections} like the admin import log: this is company-scoped, and the reconcile screen —
 * where the accountant actually notices a missing statement — asks for it by company and period.
 */
@RestController
@RequestMapping("/api/v1/ingestion")
public class ImportIssuesController {

    private final IngestionService ingestion;

    public ImportIssuesController(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    /** Locked, unreadable or wrong-party files for this company + month (newest first). */
    @GetMapping("/unresolved")
    public List<ImportView> unresolved(@RequestParam("companyId") UUID companyId,
                                       @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                       LocalDate period) {
        return ingestion.unresolvedFor(companyId, period).stream().map(ImportView::from).toList();
    }
}
