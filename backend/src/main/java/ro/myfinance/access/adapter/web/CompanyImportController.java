package ro.myfinance.access.adapter.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ro.myfinance.access.application.CompanyImportService;
import ro.myfinance.access.application.CompanyImportService.ImportResult;

/** Bulk company onboarding from a CSV (firm staff only). Partial import — see {@link CompanyImportService}. */
@RestController
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class CompanyImportController {

    private final CompanyImportService service;

    public CompanyImportController(CompanyImportService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/companies/import")
    public ImportResult importCsv(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded");
        }
        try {
            return service.importCsv(file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
