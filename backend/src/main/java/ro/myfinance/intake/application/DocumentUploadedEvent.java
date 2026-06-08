package ro.myfinance.intake.application;

import java.time.LocalDate;
import java.util.UUID;
import ro.myfinance.intake.domain.DocumentType;

/** Published after a document is uploaded; extraction listens (BANK_STATEMENT, INVOICE). */
public record DocumentUploadedEvent(UUID documentId, UUID companyId, LocalDate periodMonth,
                                    DocumentType type, String filename, byte[] bytes) {
}
