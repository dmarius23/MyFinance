package ro.myfinance.common.email;

/** Outcome of an email send, recorded on every {@link EmailHistory} row. */
public enum EmailStatus {
    /** Handed to the transport without error. */
    SENT,
    /** The transport threw; {@code error} holds the message. */
    FAILED
}
