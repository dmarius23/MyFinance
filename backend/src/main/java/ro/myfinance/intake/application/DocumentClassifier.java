package ro.myfinance.intake.application;

import ro.myfinance.intake.domain.DocumentType;

/** Determines a document's type from its bytes/metadata. Deterministic; no LLM. */
public interface DocumentClassifier {

    DocumentType classify(String filename, String contentType, byte[] bytes);

    /** Classify already-extracted plain text (e.g. recovered via OCR), using the same deterministic rules. */
    DocumentType classifyText(String text);
}
