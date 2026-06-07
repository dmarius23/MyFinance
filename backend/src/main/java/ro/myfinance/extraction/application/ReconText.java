package ro.myfinance.extraction.application;

import java.text.Normalizer;

/** Text normalization for rule matching: lowercase, strip diacritics, collapse whitespace. */
public final class ReconText {

    private ReconText() {
    }

    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String stripped = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return stripped.toLowerCase().replaceAll("\\s+", " ").strip();
    }
}
