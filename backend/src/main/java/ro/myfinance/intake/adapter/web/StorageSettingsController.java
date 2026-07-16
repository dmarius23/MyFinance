package ro.myfinance.intake.adapter.web;

import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.intake.application.DriveDocumentWriter;
import ro.myfinance.intake.application.StorageConfigService;
import ro.myfinance.intake.application.StorageConfigService.StorageConfigView;
import ro.myfinance.intake.domain.StorageMode;

/**
 * Per-tenant document storage strategy settings. TENANT_ADMIN only. {@code driveAvailable} reports whether
 * the Google service account is configured server-side (a Drive mode is only useful when it is).
 */
@RestController
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class StorageSettingsController {

    private final StorageConfigService storage;
    private final DriveDocumentWriter driveWriter;

    public StorageSettingsController(StorageConfigService storage, DriveDocumentWriter driveWriter) {
        this.storage = storage;
        this.driveWriter = driveWriter;
    }

    @GetMapping("/api/v1/settings/storage")
    public StorageResponse get() {
        return StorageResponse.of(storage.current(), driveWriter.isEnabled());
    }

    @PutMapping("/api/v1/settings/storage")
    public StorageResponse update(@RequestBody UpdateRequest req) {
        StorageConfigView view = storage.update(req.mode(), req.sharedDriveId(), req.writeRootFolderId());
        return StorageResponse.of(view, driveWriter.isEnabled());
    }

    public record UpdateRequest(@NotNull StorageMode mode, String sharedDriveId, String writeRootFolderId) {
    }

    public record StorageResponse(StorageMode mode, String sharedDriveId, String writeRootFolderId,
                                  boolean driveAvailable) {
        static StorageResponse of(StorageConfigView v, boolean driveAvailable) {
            return new StorageResponse(v.mode(), v.sharedDriveId(), v.writeRootFolderId(), driveAvailable);
        }
    }
}
