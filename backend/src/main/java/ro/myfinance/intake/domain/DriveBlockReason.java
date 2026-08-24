package ro.myfinance.intake.domain;

/**
 * Why an uploaded document must NOT be mirrored to Google Drive. The file is still stored on the server
 * (Supabase) and remains fully usable; only the Drive write is suppressed. A null reason means the
 * document is eligible to mirror. Set by {@code DocumentValidator} at upload time.
 */
public enum DriveBlockReason {
    /** Identical bytes already stored for this company + period + type. */
    DUPLICATE,
    /** The document's content belongs to a different company (CUI mismatch). */
    WRONG_COMPANY,
    /** The document's own period differs from the month it was filed under. */
    WRONG_PERIOD
}
