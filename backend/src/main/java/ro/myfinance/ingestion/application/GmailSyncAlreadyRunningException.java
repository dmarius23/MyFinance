package ro.myfinance.ingestion.application;

/** Another worker currently owns the lease for this Gmail connection. */
public class GmailSyncAlreadyRunningException extends RuntimeException {

    public GmailSyncAlreadyRunningException(String message) {
        super(message);
    }
}
