package ro.myfinance.common.whatsapp;

import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * Transport port for outbound WhatsApp messages — the channel analogue of {@code EmailSender}. Phase 1
 * ships a logging stub; a real provider (Twilio) drops in behind this port later. Implementations throw
 * to signal a delivery failure so the outbox relay retries and, on exhaustion, marks the history FAILED.
 *
 * <p>Note: with the real WhatsApp Business API, business-initiated messages must use pre-approved
 * <b>templates</b> — the {@link Message#body()} is the rendered text kept for history/display; the
 * provider adapter will map it to the approved template + variables.
 */
public interface WhatsAppSender {

    record Message(String fromName, String toPhone, String body, String templateName,
                   String templateLanguage, Map<String, String> templateVariables,
                   List<String> templateVariableOrder, UUID companyId, List<Attachment> attachments) {
        public Message(String fromName, String toPhone, String body) {
            this(fromName, toPhone, body, null, null, Map.of(), List.of(), null, List.of());
        }

        public Message {
            templateVariables = templateVariables == null ? Map.of() : Map.copyOf(templateVariables);
            templateVariableOrder = templateVariableOrder == null ? List.of() : List.copyOf(templateVariableOrder);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    record Attachment(UUID documentId) {
    }

    DeliveryReceipt send(Message message);

    /**
     * Resumable send: skips the parts an earlier attempt of the same message already delivered and reports
     * each part as it is delivered, so a retry after a partial failure never re-sends the template (or an
     * attachment) to the client. The default suits providers that deliver the whole message atomically.
     */
    default DeliveryReceipt send(Message message, DeliveryProgress progress) {
        if (progress.templateMessageId() != null) {
            return new DeliveryReceipt(progress.templateMessageId());
        }
        DeliveryReceipt receipt = send(message);
        progress.onTemplateDelivered(receipt == null ? null : receipt.providerMessageId());
        return receipt;
    }

    /** What earlier attempts of one message already delivered, and callbacks that record new progress. */
    interface DeliveryProgress {

        /** Provider id of the already-delivered template message, or null if it has not been delivered. */
        String templateMessageId();

        boolean isAttachmentDelivered(UUID documentId);

        void onTemplateDelivered(String providerMessageId);

        void onAttachmentDelivered(UUID documentId);

        /** Fresh, in-memory progress for a one-shot send with nothing delivered yet. */
        static DeliveryProgress none() {
            return new DeliveryProgress() {
                private String messageId;
                private final java.util.Set<UUID> attachments = new java.util.HashSet<>();

                @Override public String templateMessageId() { return messageId; }
                @Override public boolean isAttachmentDelivered(UUID documentId) {
                    return attachments.contains(documentId);
                }
                @Override public void onTemplateDelivered(String providerMessageId) {
                    messageId = providerMessageId;
                }
                @Override public void onAttachmentDelivered(UUID documentId) { attachments.add(documentId); }
            };
        }
    }

    record DeliveryReceipt(String providerMessageId) {
        public static DeliveryReceipt withoutProviderId() {
            return new DeliveryReceipt(null);
        }
    }
}
