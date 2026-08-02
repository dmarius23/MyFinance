package ro.myfinance.common.text;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Diacritic-insensitive normalization for Romanian free text — company names, folder segments,
 * document keywords. Unifies the {@code normalize()} helpers formerly copy-pasted in
 * {@code FolderMapper}, {@code CompanyMatcher} and {@code HeuristicDocumentClassifier}.
 *
 * <p>The shared core is {@link #stripDiacritics}: Unicode NFD decomposition with combining marks
 * removed, plus an explicit fold of the Romanian comma/cedilla letters (ș ț ş ţ, both cases) for
 * inputs that don't decompose. The two public shapes differ only in case and in whether spacing and
 * punctuation are dropped.
 */
public final class StringNormalizer {

    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Z0-9]");

    private StringNormalizer() {
    }

    /**
     * Strip diacritics while preserving case, spacing and punctuation. Null yields {@code ""}.
     */
    public static String stripDiacritics(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('ș', 's').replace('ț', 't').replace('Ș', 'S').replace('Ț', 'T')
                .replace('ş', 's').replace('ţ', 't').replace('Ş', 'S').replace('Ţ', 'T');
    }

    /**
     * Diacritic-stripped and lower-cased; spacing and punctuation preserved — for accent-insensitive
     * substring matching. Null yields {@code ""}.
     */
    public static String foldLower(String s) {
        return stripDiacritics(s).toLowerCase(Locale.ROOT);
    }

    /**
     * Diacritic-stripped, upper-cased and reduced to {@code [A-Z0-9]} (spacing and punctuation
     * dropped) — for name/token key comparisons. Null yields {@code ""}.
     */
    public static String alnumUpper(String s) {
        return NON_ALNUM.matcher(stripDiacritics(s).toUpperCase(Locale.ROOT)).replaceAll("");
    }
}
