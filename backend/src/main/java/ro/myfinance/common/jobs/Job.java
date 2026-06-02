package ro.myfinance.common.jobs;

/**
 * A unit of async work. {@code idempotencyKey} (document/period/message id) lets consumers dedupe
 * so a redelivery never double-processes. {@code payloadJson} is the job-specific arguments.
 */
public record Job(JobType type, String idempotencyKey, String payloadJson) {

    public enum JobType {
        EXTRACT_DOCUMENT,
        RECONCILE_PERIOD,
        CLASSIFY_TRANSACTIONS,
        GENERATE_REPORT,
        SEND_EMAIL,
        RUN_NOTIFICATION_RULE,
        INGEST_MAILBOX
    }
}
