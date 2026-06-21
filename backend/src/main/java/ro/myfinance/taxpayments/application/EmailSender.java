package ro.myfinance.taxpayments.application;

import java.util.List;

/**
 * Port for delivering an email. The production adapter is Amazon SES (EU); local/dev uses a logging
 * no-op. Implementations should throw to signal a delivery failure (recorded as FAILED).
 *
 * <p>Shared across modules (tax payments, document reminders, payroll). The {@link Message} carries the
 * From identity — display name (the logged-in user) and address (the accounting firm) — the recipient,
 * and any attachments (payroll attaches the pay statement / payslip / timesheet).
 */
public interface EmailSender {

    /** One file attached to an email. */
    record Attachment(String filename, String contentType, byte[] bytes) {
    }

    /** A fully-addressed email ready to send. */
    record Message(String fromName, String fromEmail, String to, String subject, String body,
                   List<Attachment> attachments) {
        public Message {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        public static Message of(String fromName, String fromEmail, String to, String subject, String body) {
            return new Message(fromName, fromEmail, to, subject, body, List.of());
        }
    }

    void send(Message message);
}
