package ro.myfinance.common.email;

/** Lifecycle of an email send, recorded on every {@link EmailHistory} row. */
public enum EmailStatus {
    /** Accepted and durably enqueued in the outbox; the worker relay will deliver it (with retries). */
    QUEUED,
    /** Delivered to the transport without error. */
    SENT,
    /** Delivery permanently failed (the outbox message exhausted its retries → DLQ); {@code error} holds why. */
    FAILED
}
