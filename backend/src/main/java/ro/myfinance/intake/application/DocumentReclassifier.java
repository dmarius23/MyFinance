package ro.myfinance.intake.application;

import java.util.Optional;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Fallback classification for documents the deterministic text classifier couldn't read — e.g. a PDF
 * with a non-extractable text layer, recovered via OCR. Returns empty when it can't improve on
 * UNCLASSIFIED (or when OCR is disabled).
 */
public interface DocumentReclassifier {

    Optional<DocumentType> tryClassify(String contentType, byte[] bytes);
}
