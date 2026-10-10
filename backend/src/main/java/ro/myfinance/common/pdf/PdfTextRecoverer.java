package ro.myfinance.common.pdf;

/**
 * Recovers text from a PDF that has no usable text layer (a scan, or a subset font with no ToUnicode
 * map) by rendering its pages and OCR-ing them. Lives in {@code common} so both the intake classifier
 * and the statement extractor can use the same tiered implementation (Tesseract first, vision fallback)
 * instead of duplicating it.
 *
 * <p>Always best-effort: returns an empty string when OCR is disabled, when the PDF already has readable
 * text, or when recovery fails — never throws.
 */
public interface PdfTextRecoverer {

    /** OCR-recovered text, or "" when OCR is disabled / unnecessary / unsuccessful. */
    String recoverText(byte[] pdf);
}
