package ro.myfinance.intake.domain;

/**
 * The filing direction of an INVOICE document, resolved from the e-Factura XML by comparing supplier vs.
 * buyer CUI against the company's CUI. Drives which Drive folder the invoice is filed into
 * (Facturi achizitii vs. Facturi emise). {@code UNKNOWN} = could not be parsed (scanned / non-XML) →
 * kept in Supabase, not mirrored.
 */
public enum InvoiceDirection {
    /** The company is the buyer — a received/purchase invoice → "Facturi achizitii". */
    RECEIVED,
    /** The company is the supplier — an issued/sales invoice → "Facturi emise". */
    ISSUED,
    /** Direction could not be determined. */
    UNKNOWN
}
