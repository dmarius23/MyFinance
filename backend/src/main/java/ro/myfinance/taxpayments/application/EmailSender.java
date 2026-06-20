package ro.myfinance.taxpayments.application;

/**
 * Port for delivering a plain-text email. The production adapter is Amazon SES (EU); local/dev uses a
 * logging no-op. Implementations should throw to signal a delivery failure (recorded as FAILED).
 */
public interface EmailSender {

    void send(String to, String subject, String body);
}
