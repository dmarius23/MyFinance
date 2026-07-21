package ro.myfinance.common.email;

/**
 * Which business channel an {@link EmailHistory} row belongs to. One shared history table is partitioned
 * by this discriminator so each module still lists only its own sends.
 */
public enum EmailKind {
    /** State-payment (tax) email — {@code relatedIds} = the tax_declaration ids covered. */
    TAX,
    /** Monthly financial report email (PDF + charts attached). */
    REPORT,
    /** Payroll email — {@code relatedIds} = the document ids attached. */
    PAYROLL,
    /** Missing-document reminder for the bank-statements & invoices hub. */
    DOCUMENT_REMINDER
}
