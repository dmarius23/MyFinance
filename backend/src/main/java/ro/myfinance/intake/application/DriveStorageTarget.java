package ro.myfinance.intake.application;

import java.util.Optional;

/**
 * Resolves the current tenant's Google Drive write target — the write-enabled document source connection
 * (MOD-15). Present only when the tenant has a Drive connection with write access; then uploaded documents
 * are mirrored into it. Implemented in the ingestion module (which owns source connections).
 */
public interface DriveStorageTarget {

    /** A Shared Drive write target: {@code sharedDriveId} is the drive, {@code rootFolderId} the base folder. */
    record Target(String sharedDriveId, String rootFolderId) {
    }

    /** The tenant's write-enabled Drive connection as a target, or empty when none/read-only. */
    Optional<Target> currentWriteTarget();
}
