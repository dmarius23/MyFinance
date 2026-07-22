package ro.myfinance.settings.domain;

/** Lifecycle of an ANAF treasury-IBAN sync run. */
public enum SyncRunStatus {
    /** Crawling ANAF + diffing (in the worker). */
    RUNNING,
    /** Crawl done; the diff awaits SUPER_ADMIN review. */
    READY_FOR_REVIEW,
    /** The approved diff has been written to the live treasury table. */
    APPLIED,
    /** Crawl failed unrecoverably (e.g. index unreachable). */
    FAILED,
    /** Discarded by the admin without applying. */
    CANCELLED
}
