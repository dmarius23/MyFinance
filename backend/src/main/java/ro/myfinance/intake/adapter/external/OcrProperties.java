package ro.myfinance.intake.adapter.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OCR fallback for PDFs whose text layer is non-extractable (e.g. Apache-FOP / Identity-H subset fonts
 * with no ToUnicode map). Disabled by default. When enabled, an unclassified PDF is rendered and OCR'd —
 * Tesseract first (local, free), then the Anthropic vision API (reusing {@code myfinance.receipt.*}) only
 * if Tesseract isn't confident — and the recovered text is re-run through the deterministic classifier.
 *
 * <pre>
 * myfinance.ocr.enabled=true
 * myfinance.ocr.tesseract-cmd=tesseract      # install: brew install tesseract tesseract-lang
 * myfinance.ocr.tesseract-lang=ron+eng
 * myfinance.ocr.max-pages=2             # classification peek
 * myfinance.ocr.statement-max-pages=25  # bank statements are read end to end
 * </pre>
 */
@ConfigurationProperties(prefix = "myfinance.ocr")
public record OcrProperties(boolean enabled, String tesseractCmd, String tesseractLang, Integer dpi,
                            Integer maxPages, Integer statementMaxPages) {

    public OcrProperties {
        tesseractCmd = (tesseractCmd == null || tesseractCmd.isBlank()) ? "tesseract" : tesseractCmd;
        tesseractLang = (tesseractLang == null || tesseractLang.isBlank()) ? "ron+eng" : tesseractLang;
        dpi = dpi == null ? 200 : dpi;
        // Classification only needs a cheap peek at the first pages…
        maxPages = maxPages == null ? 2 : maxPages;
        // …but a bank statement must be read END TO END, or the transactions past the cap are lost and
        // the opening + Σ == closing cross-check fails. Still bounded, so a pathological scan can't fan
        // out into hundreds of vision calls.
        statementMaxPages = statementMaxPages == null ? 25 : statementMaxPages;
    }
}
