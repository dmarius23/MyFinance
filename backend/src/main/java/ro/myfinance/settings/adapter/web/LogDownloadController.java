package ro.myfinance.settings.adapter.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.common.web.NotFoundException;

/**
 * Temporary debug aid: the current backend log file (this process only — not rotated archives, not the
 * worker's), downloadable by a TENANT_ADMIN from Settings. The log is process-wide and spans every
 * tenant; switch it off with {@code myfinance.logs.download-enabled=false}.
 */
@RestController
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class LogDownloadController {

    private final boolean enabled;
    private final Path logFile;

    public LogDownloadController(@Value("${myfinance.logs.download-enabled:false}") boolean enabled,
                                 @Value("${logging.file.name:}") String logFile) {
        this.enabled = enabled;
        this.logFile = logFile.isBlank() ? null : Path.of(logFile);
    }

    @GetMapping("/api/v1/settings/logs")
    public ResponseEntity<byte[]> download() throws IOException {
        if (!enabled || logFile == null || !Files.isRegularFile(logFile)) {
            throw new NotFoundException("Backend log is not available");
        }
        // Read into memory (the active file is capped by the rolling policy) so the body length is a
        // consistent snapshot even while the logger keeps appending.
        byte[] bytes = Files.readAllBytes(logFile);
        ContentDisposition cd = ContentDisposition.attachment().filename(logFile.getFileName().toString()).build();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(bytes);
    }
}
