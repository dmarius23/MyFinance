package ro.myfinance.common.whatsapp;

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

    record Message(String fromName, String toPhone, String body) {
    }

    void send(Message message);
}
