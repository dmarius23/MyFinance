package ro.myfinance.notifications.adapter.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.intake.adapter.web.DocumentDtos.DocumentResponse;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DocumentSource;
import ro.myfinance.notifications.application.NotificationService;

/**
 * Representative PWA endpoints. A representative is scoped to a single company (from the JWT) and may
 * upload documents for it; each upload notifies the firm in-app and emails the responsible accountant.
 */
@RestController
@PreAuthorize("hasRole('REPRESENTATIVE')")
public class RepPortalController {

    private final DocumentService documents;
    private final NotificationService notifications;

    public RepPortalController(DocumentService documents, NotificationService notifications) {
        this.documents = documents;
        this.notifications = notifications;
    }

    @PostMapping("/api/v1/portal/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "periodMonth", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodMonth) {
        UUID companyId = TenantContext.companyId()
                .orElseThrow(() -> new NotFoundException("No company bound to this representative"));
        LocalDate period = periodMonth != null ? periodMonth : LocalDate.now().withDayOfMonth(1);
        try {
            Document doc = documents.upload(companyId, period, file.getOriginalFilename(),
                    file.getContentType(), file.getBytes(), null, DocumentSource.REP);
            notifications.documentUploadedByRep(companyId, doc.getId(), file.getOriginalFilename());
            return DocumentResponse.from(doc);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
    }
}
