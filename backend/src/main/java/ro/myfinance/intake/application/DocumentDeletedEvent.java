package ro.myfinance.intake.application;

import java.util.UUID;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Published after a document is permanently deleted; listeners clean up derived data (snapshots,
 * declarations) and the Drive mirror copy when one exists ({@code driveFileId} may be null).
 */
public record DocumentDeletedEvent(UUID documentId, UUID companyId, DocumentType type, String driveFileId) {
}
