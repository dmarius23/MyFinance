package ro.myfinance.extraction.application;

/** Romanian fiscal code (CIF/CUI) helpers: normalization and control-digit validation. */
public final class RoFiscalCode {

    private static final int[] KEY = {7, 5, 3, 2, 1, 7, 5, 3, 2};

    private RoFiscalCode() {
    }

    /** Bare digits (drops "RO", spaces, dots); null if none. */
    public static String digits(String cif) {
        if (cif == null) {
            return null;
        }
        String d = cif.replaceAll("[^0-9]", "");
        return d.isEmpty() ? null : d;
    }

    /**
     * Validate a Romanian CUI by its control digit (key 753217532). Catches OCR/LLM misreads and
     * non-fiscal numbers, so we never store (or flag on) a hallucinated code.
     */
    public static boolean isValidCui(String cif) {
        String d = digits(cif);
        if (d == null || d.length() < 2 || d.length() > 10) {
            return false;
        }
        int control = d.charAt(d.length() - 1) - '0';
        String num = d.substring(0, d.length() - 1);
        long sum = 0;
        for (int i = num.length() - 1, ki = KEY.length - 1; i >= 0 && ki >= 0; i--, ki--) {
            sum += (long) (num.charAt(i) - '0') * KEY[ki];
        }
        long c = (sum * 10) % 11;
        if (c == 10) {
            c = 0;
        }
        return c == control;
    }
}
