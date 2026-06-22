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

    private CompanyMatcher() {
    }

    static Boolean present(String text, String companyCui, String companyName) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cuiDigits = companyCui == null ? "" : companyCui.replaceAll("\\D", "");
        // Match the CUI as a standalone number (printed as "c.f. 49443957"), NOT as a substring of all
        // concatenated digits — otherwise an amount like "49 443 957.00" would false-match the CUI.
        boolean haveCui = cuiDigits.length() >= 4;
        if (haveCui && Pattern.compile("(?<!\\d)" + Pattern.quote(cuiDigits) + "(?!\\d)").matcher(text).find()) {
            return true;
        }
        String key = coreName(companyName);
        boolean haveName = key != null;
        if (haveName && normalize(text).contains(key)) {
            return true;
        }
        if (!haveCui && !haveName) {
            return null; // nothing to match on — can't verify
        }
        return false;
    }

    /**
     * The full distinctive company name, normalized, with leading/trailing legal forms stripped
     * (SC… / …SRL/SA/PFA/…). Matching the whole core — not a single word — avoids a common word in the
     * name (e.g. "Client" in "Client Doi SRL") false-matching an accounting term in the document
     * (account 4111 CLIENTI). Returns null when the core is too short to be distinctive (&lt; 5 chars).
     */
    private static String coreName(String name) {
        if (name == null) {
            return null;
        }
        String n = normalize(name);
        n = n.replaceFirst("^SC(?=.{5,}$)", "");
        n = n.replaceFirst("(?<=.{5})(SRLD|SRL|SNC|SCS|SCA|SA|PFA|IF|II)$", "");
        return n.length() >= 5 ? n : null;
    }

    private static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('ș', 's').replace('ț', 't').replace('Ș', 'S').replace('Ț', 'T')
                .toUpperCase();
        return NON_ALNUM.matcher(n).replaceAll("");
    }
}
