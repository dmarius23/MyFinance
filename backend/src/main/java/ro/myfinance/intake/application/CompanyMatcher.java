package ro.myfinance.intake.application;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Decides whether an extracted PDF text belongs to a given company. A payslip (fluturaș) prints only the
 * company name, never the fiscal code; a balance sheet prints both. So a document matches if EITHER the
 * company's CUI (digits) OR a distinctive word of its name appears in the text. Returns {@code null} when
 * it can't be determined (no text, or nothing to match on) — callers treat null as "can't verify, allow".
 */
final class CompanyMatcher {

    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Z0-9]");
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    private CompanyMatcher() {
    }

    static Boolean present(String text, String companyCui, String companyName) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String textDigits = text.replaceAll("\\D", "");
        String cuiDigits = companyCui == null ? "" : companyCui.replaceAll("\\D", "");
        boolean haveCui = cuiDigits.length() >= 2;
        if (haveCui && textDigits.contains(cuiDigits)) {
            return true;
        }
        String key = significantToken(companyName);
        boolean haveName = key != null;
        if (haveName && normalize(text).contains(key)) {
            return true;
        }
        if (!haveCui && !haveName) {
            return null; // nothing to match on — can't verify
        }
        return false;
    }

    /** The longest alphabetic word of the name (≥4 chars, e.g. "INNOVATECODE"), normalized; null if none. */
    private static String significantToken(String name) {
        if (name == null) {
            return null;
        }
        String best = null;
        var m = WORD.matcher(name);
        while (m.find()) {
            String w = normalize(m.group());
            if (w.length() >= 4 && (best == null || w.length() > best.length())) {
                best = w;
            }
        }
        return best;
    }

    private static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('ș', 's').replace('ț', 't').replace('Ș', 'S').replace('Ț', 'T')
                .toUpperCase();
        return NON_ALNUM.matcher(n).replaceAll("");
    }
}
