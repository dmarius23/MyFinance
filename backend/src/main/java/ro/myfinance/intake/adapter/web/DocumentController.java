package ro.myfinance.intake.adapter.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ro.myfinance.intake.adapter.web.DocumentDtos.DocumentResponse;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.application.DocumentService.DocumentContent;

/** Document intake. Firm staff (admin/employee) only. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/documents")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse upload(@PathVariable UUID companyId,
                                   @RequestParam("periodMonth") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodMonth,
                                   @RequestParam("file") MultipartFile file,
                                   @RequestParam(value = "type", required = false) ro.myfinance.intake.domain.DocumentType type) {
        try {
            return DocumentResponse.from(service.upload(companyId, periodMonth,
                    file.getOriginalFilename(), file.getContentType(), file.getBytes(), type));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
    }

    @GetMapping
    public List<DocumentResponse> list(@PathVariable UUID companyId,
                                       @RequestParam(value = "period", required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return service.list(companyId, period).stream().map(DocumentResponse::from).toList();
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID companyId, @PathVariable UUID id) {
        DocumentContent c = service.getContent(id);
        // ContentDisposition encodes/sanitizes the user-supplied filename (no header injection).
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(c.document().getOriginalFilename(), java.nio.charset.StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(c.document().getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(c.bytes());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID companyId, @PathVariable UUID id) {
        service.delete(id);
    }

    @PatchMapping("/{id}/type")
    public DocumentResponse changeType(@PathVariable UUID companyId, @PathVariable UUID id,
                                       @RequestBody DocumentDtos.ChangeTypeRequest r) {
        return DocumentResponse.from(service.changeType(companyId, id, r.type()));
    }

    @PostMapping("/reclassify")
    public int reclassify(@PathVariable UUID companyId,
                          @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return service.reclassify(companyId, period);
    }
}
