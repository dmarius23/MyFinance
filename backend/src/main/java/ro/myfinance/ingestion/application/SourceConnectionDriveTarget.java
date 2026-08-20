package ro.myfinance.ingestion.application;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.ingestion.adapter.persistence.SourceConnectionRepository;
import ro.myfinance.ingestion.domain.SourceConnection;
import ro.myfinance.intake.application.DriveStorageTarget;
import ro.myfinance.intake.domain.DrivePurpose;

/**
 * Resolves the mirror write target from the tenant's write-enabled Google Drive source connection for a
 * given filing purpose (DECLARATIONS or ACCOUNTING). The connection's {@code rootFolderId} is the firm's
 * Shared Drive root (for a Shared Drive, the root folder id equals the drive id), so it serves as both the
 * drive id and the base write folder. Empty when the tenant has no write-enabled Drive connection of that
 * purpose.
 */
@Component
public class SourceConnectionDriveTarget implements DriveStorageTarget {

    private static final String GOOGLE_DRIVE = "GOOGLE_DRIVE";

    private final SourceConnectionRepository connections;

    public SourceConnectionDriveTarget(SourceConnectionRepository connections) {
        this.connections = connections;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Target> writeTargetFor(DrivePurpose purpose) {
        return connections.findByProviderAndPurposeAndWriteEnabledTrue(GOOGLE_DRIVE, purpose.name()).stream()
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .filter(c -> c.getRootFolderId() != null && !c.getRootFolderId().isBlank())
                .findFirst()
                .map(SourceConnection::getRootFolderId)
                .map(root -> new Target(root, root));
    }
}
