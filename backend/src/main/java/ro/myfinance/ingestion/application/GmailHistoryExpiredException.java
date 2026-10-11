package ro.myfinance.ingestion.application;

/** Gmail no longer retains the requested history cursor; an explicit date backfill is required. */
public class GmailHistoryExpiredException extends RuntimeException {

    public GmailHistoryExpiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
