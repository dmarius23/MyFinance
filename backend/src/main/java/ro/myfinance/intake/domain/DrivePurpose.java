package ro.myfinance.intake.domain;

/**
 * Which of a tenant's Drive structures a document is filed into (see the Drive Filing v2 design). A tenant
 * has up to one write-enabled Drive connection per purpose.
 */
public enum DrivePurpose {
    /** Declarations + payrolls + reports — the "Declaratii {year}" all-years drive. */
    DECLARATIONS,
    /** Bank statements + invoices — the "Folder Contabilitate Clienti" drive. */
    ACCOUNTING
}
