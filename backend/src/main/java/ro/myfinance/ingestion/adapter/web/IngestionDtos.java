package ro.myfinance.ingestion.adapter.web;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import ro.myfinance.ingestion.domain.ImportFile;
import ro.myfinance.ingestion.domain.SourceConnection;

public final class IngestionDtos {

    private IngestionDtos() {
    }

    public record CreateConnectionRequest(@NotBlank String provider, @NotBlank String displayName,
                                          @NotBlank String rootFolderId, String forcedType, String config) {
    }

    public record UpdateConnectionRequest(String displayName, String rootFolderId, String forcedType,
                                          String config, String status) {
    }

    public record ConnectionView(UUID id, String provider, String displayName, String rootFolderId,
                                 String forcedType, String status, Instant lastSyncedAt, String lastResult) {
        public static ConnectionView from(SourceConnection c) {
            return new ConnectionView(c.getId(), c.getProvider(), c.getDisplayName(), c.getRootFolderId(),
                    c.getForcedType(), c.getStatus(), c.getLastSyncedAt(), c.getLastResult());
        }
    }

    public record SyncResponse(int imported, int needsReview, int skipped, int failed) {
    }

    public record ImportView(UUID id, String filename, String sourcePath, String status, String detail,
                             UUID documentId, Instant createdAt) {
        public static ImportView from(ImportFile f) {
            return new ImportView(f.getId(), f.getFilename(), f.getSourcePath(), f.getStatus(),
                    f.getDetail(), f.getDocumentId(), f.getCreatedAt());
        }
    }
}
