package ro.myfinance.ingestion.application;

/** Gmail rejected the grant and the tenant must authorize the mailbox again. */
public class GmailAuthorizationRevokedException extends RuntimeException {
    public GmailAuthorizationRevokedException(String message, Throwable cause) {
        super(message, cause);
    }
}
