package ro.myfinance.intake.application;

import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ro.myfinance.common.whatsapp.WhatsAppMediaSource;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.intake.adapter.persistence.DocumentRepository;
import ro.myfinance.intake.domain.Document;

/** Loads only tenant/company-owned documents for outbound WhatsApp media. */
@Component
public class DocumentWhatsAppMediaSource implements WhatsAppMediaSource {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf", "image/png", "image/jpeg", "image/webp");

    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final long maxBytes;

    public DocumentWhatsAppMediaSource(DocumentRepository documents, DocumentStorage storage,
                                       @Value("${myfinance.whatsapp.max-attachment-bytes:20971520}")
                                       long maxBytes) {
        this.documents = documents;
        this.storage = storage;
        this.maxBytes = maxBytes;
    }

    @Override
    public MediaInfo describe(UUID companyId, UUID documentId) {
        Document document = owned(companyId, documentId);
        validate(document);
        return new MediaInfo(documentId, document.getOriginalFilename(), document.getContentType(),
                document.getSizeBytes());
    }

    @Override
    public Media load(UUID companyId, UUID documentId) {
        Document document = owned(companyId, documentId);
        validate(document);
        byte[] bytes = storage.retrieve(document.getStorageKey());
        if (bytes.length > maxBytes) {
            throw new ConflictException("WhatsApp attachment exceeds the configured size limit");
        }
        return new Media(documentId, document.getOriginalFilename(), document.getContentType(), bytes);
    }

    private Document owned(UUID companyId, UUID documentId) {
        Document document = documents.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        if (!companyId.equals(document.getCompanyId())) {
            throw new NotFoundException("Document not found");
        }
        return document;
    }

    private void validate(Document document) {
        if (!ALLOWED_TYPES.contains(document.getContentType())) {
            throw new ConflictException("This document type cannot be sent through WhatsApp");
        }
        if (document.getSizeBytes() > maxBytes) {
            throw new ConflictException("WhatsApp attachment exceeds the configured size limit");
        }
    }
}
