package ro.myfinance.settings.adapter.external;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure (no network, no PDFBox) parsing of the ANAF IBAN catalogue. All extraction is deterministic:
 * <ul>
 *   <li>the county list is the set of {@code iban2014/*.htm} links on the index page, minus the handful
 *       of non-county pages (buget stat/local, bass, fnuass, somaj);</li>
 *   <li>each county page links one {@code iban_TREZ*.pdf} per treasury;</li>
 *   <li>inside a treasury PDF the budget code is embedded in the IBAN string itself, so a target IBAN is
 *       located by matching {@code RO\d\dTREZ<3-digit unit><budget-code>...} — no row/column alignment.</li>
 * </ul>
 * The four budget codes we extract: 5503 (cont unic — impozit/CAS/CASS), 20A470300 (CAM),
 * 20A100101 (TVA intern), 20A100102 (TVA extern).
 */
public final class AnafIbanParser {

    public static final String CODE_5503 = "5503";
    public static final String CODE_CAM = "20A470300";
    public static final String CODE_TVA_INTERN = "20A100101";
    public static final String CODE_TVA_EXTERN = "20A100102";

    /** {@code iban2014/*.htm} pages that are NOT counties (budgets/funds), excluded from discovery. */
    private static final Set<String> NON_COUNTY_PAGES =
            Set.of("bass", "bugetlocal", "bugetstat", "fnuass", "somaj");

    /** Leading words in a treasury header that precede the town name (diacritics-insensitive). */
    private static final Set<String> HEADER_NOISE = Set.of(
            "trezoreria", "trezorerie", "operativa", "operativ", "municipiul", "municipiu",
            "orasul", "oras", "comuna", "judeteana", "judetean", "a", "mun");

    private static final Pattern COUNTY_HTM =
            Pattern.compile("iban2014/([A-Za-z_]+)\\.htm", Pattern.CASE_INSENSITIVE);
    private static final Pattern PDF_HREF =
            Pattern.compile("href=\"([^\"]*iban_TREZ[^\"]*\\.pdf)\"", Pattern.CASE_INSENSITIVE);

    private AnafIbanParser() {
    }

    /** Absolute URLs of the per-county pages found on the index page (dedup, non-counties removed). */
    public static List<String> countyPageUrls(String indexHtml, String baseUrl) {
        Set<String> files = new LinkedHashSet<>();
        Matcher m = COUNTY_HTM.matcher(indexHtml);
        while (m.find()) {
            String file = m.group(1);
            if (!NON_COUNTY_PAGES.contains(file.toLowerCase(Locale.ROOT))) {
                files.add(file);
            }
        }
        List<String> urls = new ArrayList<>();
        for (String file : files) {
            urls.add(join(baseUrl, file + ".htm"));
        }
        return urls;
    }

    /** Absolute URLs of every treasury PDF linked on a county page (http upgraded to https, deduped). */
    public static List<String> pdfLinks(String countyHtml, String baseUrl) {
        Set<String> urls = new LinkedHashSet<>();
        Matcher m = PDF_HREF.matcher(countyHtml);
        while (m.find()) {
            urls.add(absolute(m.group(1), baseUrl));
        }
        return new ArrayList<>(urls);
    }

    /**
     * The 24-char treasury IBAN carrying {@code budgetCode}, or null if absent. The code sits right after
     * the 3-character treasury-unit segment: {@code RO<2 check>TREZ<unit><budgetCode><suffix>}.
     */
    public static String ibanByCode(String pdfText, String budgetCode) {
        if (pdfText == null) {
            return null;
        }
        Matcher m = Pattern.compile("RO\\d{2}TREZ[0-9A-Z]{3}" + Pattern.quote(budgetCode) + "[0-9A-Z]*")
                .matcher(pdfText);
        if (m.find()) {
            String token = m.group();
            return token.length() >= 24 ? token.substring(0, 24) : token;
        }
        return null;
    }

    /** The fiscal residence (town) from the PDF header line, e.g. "Trezorerie operativa Municipiul Alba Iulia" -> "Alba Iulia". */
    public static String residence(String pdfText) {
        if (pdfText == null) {
            return null;
        }
        for (String raw : pdfText.split("\\R")) {
            String line = raw.trim();
            if (line.regionMatches(true, 0, "Trezorer", 0, 8)) {
                String town = stripHeaderNoise(line);
                if (!town.isBlank()) {
                    return town;
                }
            }
        }
        return null;
    }

    /** Drop the contiguous leading treasury-type words, keep the rest (the town) with original casing. */
    private static String stripHeaderNoise(String line) {
        String[] words = line.split("\\s+");
        int i = 0;
        while (i < words.length && HEADER_NOISE.contains(normalize(words[i]))) {
            i++;
        }
        return String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length)).trim();
    }

    private static String normalize(String s) {
        String stripped = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return stripped.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private static String absolute(String href, String baseUrl) {
        String url = href.startsWith("http") ? href : join(baseUrl, href);
        return url.replaceFirst("^http://", "https://");
    }

    private static String join(String baseUrl, String tail) {
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String t = tail.startsWith("/") ? tail.substring(1) : tail;
        return b + "/" + t;
    }
}
