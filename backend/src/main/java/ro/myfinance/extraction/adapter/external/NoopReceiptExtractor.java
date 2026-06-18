package ro.myfinance.extraction.adapter.external;

import ro.myfinance.extraction.application.ParsedReceipt;
import ro.myfinance.extraction.application.ReceiptExtractor;

/** Used when no receipt-OCR provider is configured: image receipts get empty fields → NEEDS_REVIEW. */
public class NoopReceiptExtractor implements ReceiptExtractor {

    @Override
    public ParsedReceipt extract(byte[] image, String mediaType) {
        return ParsedReceipt.empty();
    }
}
