package ro.myfinance.intake.application;

import java.util.Optional;
import ro.myfinance.intake.domain.DrivePurpose;

/**
 * Resolves the current tenant's Google Drive write target for a given filing purpose — the write-enabled
 * document source connection (MOD-15) of that purpose. A tenant may have up to two: a DECLARATIONS drive
 * and an ACCOUNTING drive. Present only when the tenant has a write-enabled Drive connection for the
 * requested purpose. Implemented in the ingestion module (which owns source connections).
 */
public interface DriveStorageTarget {

    /**
     * A Shared Drive write target: {@code sharedDriveId} is the drive, {@code rootFolderId} the base folder.
     * {@code rootIsYearFolder} (DECLARATIONS only) → the root already IS the "Declaratii {year}" folder, so
     * the filing must not create that level.
     */
    record Target(String sharedDriveId, String rootFolderId, boolean rootIsYearFolder) {
    }

    /** The tenant's write-enabled Drive connection for {@code purpose} as a target, or empty when none. */
    Optional<Target> writeTargetFor(DrivePurpose purpose);
}
