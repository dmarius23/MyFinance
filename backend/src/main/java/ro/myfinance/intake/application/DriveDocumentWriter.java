package ro.myfinance.intake.application;

import java.util.List;
import java.util.UUID;

/**
 * Port for writing a document's bytes into a tenant's Google Shared Drive (the mirror / primary target).
 * The adapter resolves (creating if needed) the folder path under the root and uploads the file, tagging
 * it with the originating document id so ingestion never re-imports an app-written file.
 */
public interface DriveDocumentWriter {

    /** True when the Google service-account credentials are configured server-side. */
    boolean isEnabled();

    /**
     * Ensure {@code folderSegments} exist under {@code rootFolderId} in the shared drive and create the
     * file there. Returns the Drive file id. Throws on failure (caller treats as best-effort).
     */
    String put(String sharedDriveId, String rootFolderId, List<String> folderSegments,
               String name, String contentType, byte[] bytes, UUID documentId);

    /** Delete a previously written file. Best-effort; a missing file is not an error. */
    void delete(String fileId);
}
