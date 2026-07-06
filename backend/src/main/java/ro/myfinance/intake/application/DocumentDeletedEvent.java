package ro.myfinance.intake.application;

import java.util.UUID;
import ro.myfinance.intake.domain.DocumentType;

/** Published after a document is permanently deleted; listeners clean up derived data (snapshots, declarations). */
public record DocumentDeletedEvent(UUID documentId, UUID companyId, DocumentType type) {
}
