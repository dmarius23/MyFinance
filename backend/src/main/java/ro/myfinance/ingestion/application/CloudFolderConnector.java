package ro.myfinance.ingestion.application;

import java.time.Instant;
import java.util.List;
import ro.myfinance.ingestion.domain.SourceConnection;

/**
 * MOD-15 — a provider-agnostic view of a watched cloud folder (Google Drive, OneDrive, …). Adapters
 * implement listing and downloading; the ingestion service handles mapping, dedupe and upload. Kept
 * deliberately thin so a fake in-memory connector can drive the whole pipeline in tests.
 */
public interface CloudFolderConnector {

    /** Provider this connector serves, matching {@link SourceConnection#getProvider()}. */
    String provider();

    /** Files under the connection's root folder (recursively), newest first. {@code cursor} may be used
     *  for incremental polling; null lists everything. Returns the files plus the next cursor. */
    Listing list(SourceConnection connection, String cursor);

    /**
     * Recursively list files under {@code startFolderId} (a folder below the root), so a per-company
     * sync can crawl only that company's folder instead of the whole drive. {@code path} on the
     * returned files is relative to {@code startFolderId}. Default: full root crawl (connectors that
     * don't scope just ignore the start folder).
     */
    default Listing list(SourceConnection connection, String startFolderId, String cursor) {
        return list(connection, cursor);
    }

    /**
     * Direct child folders of {@code parentId} — used to locate a company's folder under the root.
     * Default: empty (the caller then falls back to a full crawl).
     */
    default List<Folder> subfolders(SourceConnection connection, String parentId) {
        return List.of();
    }

    /** A folder in the watched tree (id + display name). */
    record Folder(String id, String name) {
    }

    /** Download a file's bytes. */
    byte[] download(SourceConnection connection, RemoteFile file);

    /** A file in the watched folder. {@code path} is the folder path relative to the root (segments
     *  joined by '/'), used to resolve the company and period. */
    record RemoteFile(String id, String name, String path, String mimeType, long size,
                      String etag, Instant modifiedTime) {
    }

    record Listing(List<RemoteFile> files, String nextCursor) {
    }
}
