package ro.myfinance.extraction.application;

/** Port: one implementation per bank. Deterministic; no LLM. */
public interface BankStatementParser {

    /** True if this parser recognizes the statement from its extracted text. */
    boolean supports(String pdfText);

    /** Parse the PDF bytes into a normalized statement. */
    ParsedStatement parse(byte[] pdf);
}
