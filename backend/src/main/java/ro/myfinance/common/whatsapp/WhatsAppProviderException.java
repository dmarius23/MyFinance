package ro.myfinance.common.whatsapp;

/** Provider failure annotated with whether retrying the outbox delivery can succeed. */
public class WhatsAppProviderException extends RuntimeException {

    private final boolean retryable;

    public WhatsAppProviderException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public WhatsAppProviderException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
