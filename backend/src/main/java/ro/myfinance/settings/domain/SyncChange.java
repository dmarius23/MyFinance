package ro.myfinance.settings.domain;

/** How one scraped treasury compares to the live reference data. */
public enum SyncChange {
    /** No live row for this residence yet. */
    ADDED,
    /** A live row exists but at least one IBAN differs. */
    CHANGED,
    /** Live row already matches the scraped IBANs. */
    UNCHANGED,
    /** This treasury could not be fetched/parsed. */
    ERROR
}
