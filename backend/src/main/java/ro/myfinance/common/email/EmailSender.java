package ro.myfinance.common.email;

import java.util.List;

/**
 * The single port for delivering an email, shared by every module that sends one (tax payments, reports,
 * payroll, document reminders, upload notifications). The production adapter is Amazon SES in an EU region
 * ({@link SesEmailSender}); dev/local uses {@link LoggingEmailSender} (logs, no delivery). Implementations
 * throw to signal a delivery failure so the caller records it as FAILED.
 *
 * <p>The {@link Message} carries the From identity — display name (typically the logged-in user) and
 * address (the accounting firm's configured sender) — resolved centrally by
 * {@code access.application.EmailEnvelopeService}, plus the recipient and any attachments (e.g. payroll
 * attaches the pay statement / payslip / timesheet; reports attach the PDF + charts).
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
