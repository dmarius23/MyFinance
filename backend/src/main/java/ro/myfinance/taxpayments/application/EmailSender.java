package ro.myfinance.taxpayments.application;

import java.util.List;

/**
 * Port for delivering a plain-text email, optionally with file attachments. The production adapter is
 * Amazon SES (EU); local/dev uses a logging no-op. Implementations should throw to signal a delivery
 * failure (recorded as FAILED).
 *
 * <p>Shared across modules (tax payments, document reminders, payroll) — payroll attaches the payslip,
 * pay statement and timesheet documents.
 */
public interface EmailSender {

    /** One file attached to an email. */
    record Attachment(String filename, String contentType, byte[] bytes) {
    }

    /** Send with attachments (the primary operation). */
    void send(String to, String subject, String body, List<Attachment> attachments);

    /** Convenience for plain-text emails without attachments. */
    default void send(String to, String subject, String body) {
        send(to, subject, body, List.of());
    }
}
