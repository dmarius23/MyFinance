package ro.myfinance.taxpayments.domain;

/**
 * Tax categories that map 1:1 to the per-residence treasury IBAN columns (settings). Obligations are
 * grouped into payment lines by the IBAN their category resolves to, so several categories can share
 * one line (e.g. impozit + CAS + CASS all pointed at the "cont unic" 55.03 account).
 */
public enum TaxCategory {
    /** Impozit pe profit/micro (D100) and impozit pe venit din salarii (D112) — buget de stat / cont unic. */
    IMPOZIT,
    CAS,
    CASS,
    CAM,
    /** TVA for internal operations (ANAF budget code 20A100101 -> {@code iban_tva}). */
    TVA,
    /** TVA for external operations — imports / intra-community (20A100102 -> {@code iban_tva_extern}). */
    TVA_EXTERN
}
