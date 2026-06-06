package ro.myfinance.intake.adapter.web;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.application.DocumentService.CompanyDocSummary;

/** Per-company document summary for the Statements list. Firm staff only. */
@RestController
@RequestMapping("/api/v1/documents")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class DocumentSummaryController {

    private final DocumentService service;

    public DocumentSummaryController(DocumentService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public List<CompanyDocSummary> summary(@RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return service.summary(period);
    }
}
