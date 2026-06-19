package ro.myfinance.extraction.application;

/**
 * Port: extract matching-relevant fields from a photographed receipt image. Phase A uses a vision
 * LLM; later a cheap OCR tier runs first and only escalates here on low confidence (hybrid). Never
 * throws — returns {@link ParsedReceipt#empty()} on failure so extraction degrades to NEEDS_REVIEW.
 */
public interface ReceiptExtractor {

    /**
     * @param ownCompanyCui the fiscal code of the company the receipt should belong to; the extractor
     *                      sets clientMatchesCompany by comparing the printed CIF CLIENT to it. May be null.
     */
    ParsedReceipt extract(byte[] image, String mediaType, String ownCompanyCui);
}
