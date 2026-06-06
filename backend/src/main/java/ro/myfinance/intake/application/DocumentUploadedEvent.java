package ro.myfinance.intake.application;

import java.time.LocalDate;
import java.util.UUID;
import ro.myfinance.intake.domain.DocumentType;

/** Published synchronously after a document is uploaded; extraction listens for BANK_STATEMENT. */
public record DocumentUploadedEvent(UUID documentId, UUID companyId, LocalDate periodMonth,
                                    DocumentType type, byte[] bytes) {
}
