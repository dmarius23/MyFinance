package ro.myfinance.extraction.application;

/** Port: extract matching-relevant fields from an invoice PDF. Deterministic; no LLM. */
public interface InvoiceExtractor {

    ParsedInvoice extract(byte[] pdf);
}
