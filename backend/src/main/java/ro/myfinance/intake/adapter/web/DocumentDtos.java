package ro.myfinance.intake.adapter.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import ro.myfinance.intake.domain.Document;

public final class DocumentDtos {

    private DocumentDtos() {
    }

    public record DocumentResponse(UUID id, String type, String status, String originalFilename,
                                   String contentType, long sizeBytes, LocalDate periodMonth,
                                   UUID uploadedBy, Instant uploadedAt) {
        public static DocumentResponse from(Document d) {
            return new DocumentResponse(d.getId(), d.getType().name(), d.getStatus().name(),
                    d.getOriginalFilename(), d.getContentType(), d.getSizeBytes(), d.getPeriodMonth(),
                    d.getUploadedBy(), d.getUploadedAt());
        }
    }
}
