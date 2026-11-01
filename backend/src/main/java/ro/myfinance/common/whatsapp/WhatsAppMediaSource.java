package ro.myfinance.common.whatsapp;

import java.util.UUID;

/** Tenant-scoped source for documents referenced by an outbound WhatsApp media message. */
public interface WhatsAppMediaSource {

    MediaInfo describe(UUID companyId, UUID documentId);

    Media load(UUID companyId, UUID documentId);

    record MediaInfo(UUID documentId, String filename, String contentType, long sizeBytes) {
    }

    record Media(UUID documentId, String filename, String contentType, byte[] bytes) {
    }
}
