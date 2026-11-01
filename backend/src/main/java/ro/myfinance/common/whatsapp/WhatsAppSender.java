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
                   UUID companyId, List<Attachment> attachments) {
        public Message(String fromName, String toPhone, String body) {
            this(fromName, toPhone, body, null, null, Map.of(), null, List.of());
        }

        public Message {
            templateVariables = templateVariables == null ? Map.of() : Map.copyOf(templateVariables);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    record Attachment(UUID documentId) {
    }

    DeliveryReceipt send(Message message);

    record DeliveryReceipt(String providerMessageId) {
        public static DeliveryReceipt withoutProviderId() {
            return new DeliveryReceipt(null);
        }
    }
}
