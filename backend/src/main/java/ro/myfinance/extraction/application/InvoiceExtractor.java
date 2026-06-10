package ro.myfinance.extraction.application;

/** Port: extract matching-relevant fields from an invoice PDF. Deterministic; no LLM. */
public interface InvoiceExtractor {

    /**
     * @param ownCompanyName the legal name of the company the document belongs to; the supplier
     *                       (issuing party) is the counterparty, so this name is never returned as the
     *                       supplier. May be null (no exclusion).
     */
    ParsedInvoice extract(byte[] pdf, String ownCompanyName);

    /** Convenience without own-company exclusion (used by tests and callers lacking the context). */
    default ParsedInvoice extract(byte[] pdf) {
        return extract(pdf, null);
    }
}
