package ro.myfinance.ingestion.application;

/** A Gmail rate-limit or temporary transport failure that should be retried by the outbox. */
public class GmailTransientException extends RuntimeException {
    public GmailTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
