package ro.myfinance.ingestion.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ro.myfinance.common.text.StringNormalizer;
import ro.myfinance.company.domain.Company;
import ro.myfinance.ingestion.application.CloudFolderConnector.RemoteFile;

/**
 * Pure mapping rules for the agreed layout {@code <root>/<company name or CUI>/[YYYY-MM]/files}:
 * resolve the client company from the first path segment (matched by CUI digits or a distinctive part
 * of the legal name) and the period from a {@code YYYY-MM} segment (else the file's modified month).
 * No I/O — fully unit-testable.
 */
public final class FolderMapper {

    /** Year + month together in one segment, e.g. 2026-05, 2026_05, 202605. */
    private static final Pattern COMBINED = Pattern.compile("(20\\d{2})[-_.]?(0[1-9]|1[0-2])(?!\\d)");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(20\\d{2})(?!\\d)");
    /** A leading month number not part of a longer number, e.g. "04 Aprilie" → 04, "12" → 12. */
    private static final Pattern LEAD_MONTH = Pattern.compile("^\\s*(0?[1-9]|1[0-2])(?!\\d)");
    /** A trimester marker in an interim-balance folder, e.g. "T1", "T 2" in "Bilant interimar T2 an 2026". */
    private static final Pattern TRIMESTER = Pattern.compile("(?i)(?<![a-z0-9])T\\s?([1-4])(?![0-9])");
    private static final java.util.Map<String, Integer> RO_MONTHS = java.util.Map.ofEntries(
            java.util.Map.entry("IANUARIE", 1), java.util.Map.entry("FEBRUARIE", 2),
            java.util.Map.entry("MARTIE", 3), java.util.Map.entry("APRILIE", 4),
            java.util.Map.entry("MAI", 5), java.util.Map.entry("IUNIE", 6),
            java.util.Map.entry("IULIE", 7), java.util.Map.entry("AUGUST", 8),
            java.util.Map.entry("SEPTEMBRIE", 9), java.util.Map.entry("OCTOMBRIE", 10),
            java.util.Map.entry("NOIEMBRIE", 11), java.util.Map.entry("DECEMBRIE", 12));

    private FolderMapper() {
    }

    /**
     * Resolve the company a file belongs to. Folder-path segments are tried first (a company-named folder,
     * as in {@code Bilant …/<Company>/…}), then the <em>filename</em> — which is where some layouts carry
     * the identity: declarations embed the CUI ({@code D700_44570402_2026_07 …}) and payroll files embed
     * the company name ({@code fluturas#_STONEAGE INDUSTRY SRL_2026_07 …}). Both are handled by
     * {@link #matchesCompany} (CUI digits or a distinctive part of the legal name).
     */
    public static Optional<UUID> resolveCompany(RemoteFile file, List<Company> companies) {
        for (String candidate : matchCandidates(file)) {
            for (Company c : companies) {
                if (matchesCompany(candidate, c)) {
                    return Optional.of(c.getId());
                }
            }
        }
        return Optional.empty();
    }

    /** Folder-path segments (outermost first) plus the filename — the strings we try to identify a company in. */
    private static List<String> matchCandidates(RemoteFile file) {
        List<String> candidates = new java.util.ArrayList<>(segments(file));
        if (file.name() != null && !file.name().isBlank()) {
            candidates.add(file.name());
        }
        return candidates;
    }

    /**
     * Whether a folder-path segment identifies {@code c} — by its CUI digits or a distinctive part of
     * its legal name. Used both to resolve a file's company and to locate a company's folder under the
     * root for a scoped crawl.
     */
    public static boolean matchesCompany(String segment, Company c) {
        String cuiDigits = digits(c.getCui());
        if (cuiDigits.length() >= 4 && digits(segment).contains(cuiDigits)) {
            return true;
        }
        String core = coreName(c.getLegalName());
        if (core == null) {
            return false;
        }
        String seg = StringNormalizer.alnumUpper(segment);
        // The usual case: the folder/file text contains the company's distinctive core.
        if (seg.contains(core)) {
            return true;
        }
        // Or the folder is a shortened form of the name (e.g. a balance folder "INNOVATECODE" for
        // "INNOVATECODE IT SRL"): the core contains the segment. Guarded by a length floor so short
        // tokens (e.g. "BIT IT") don't collide with an unrelated longer name.
        return seg.length() >= 6 && core.contains(seg);
    }

    /**
     * Resolve the period (first of month) from the folder path. Handles a combined {@code YYYY-MM}
     * segment, and a separate year segment ({@code 2026}) plus a month segment given as a leading
     * number ({@code 04 Aprilie}) or a Romanian month name ({@code Aprilie}). Falls back to the file's
     * modified month when the path carries no period.
     */
    public static LocalDate resolvePeriod(RemoteFile file) {
        List<String> segs = segments(file);
        // 1) Year + month in one segment.
        for (String s : segs) {
            Matcher m = COMBINED.matcher(s);
            if (m.find()) {
                return ym(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            }
        }
        // 2) Separate year and month segments.
        Integer year = null;
        Integer month = null;
        for (String s : segs) {
            if (year == null) {
                Matcher y = YEAR.matcher(s);
                if (y.find()) {
                    year = Integer.parseInt(y.group(1));
                }
            }
            if (month == null) {
                Integer mo = monthOf(s);
                if (mo != null) {
                    month = mo;
                }
            }
        }
        if (year != null && month != null) {
            return ym(year, month);
        }
        // 2b) Interim-balance folders carry a trimester, not a calendar month: "Bilant interimar T2 an 2026".
        //     Map the quarter to its end month (T1→Mar, T2→Jun, T3→Sep, T4→Dec).
        if (year != null) {
            for (String s : segs) {
                Integer quarter = quarterOf(s);
                if (quarter != null) {
                    return ym(year, quarter * 3);
                }
            }
        }
        // 3) Fallback: the file's modified month.
        LocalDate d = file.modifiedTime() != null
                ? file.modifiedTime().atZone(ZoneOffset.UTC).toLocalDate()
                : LocalDate.now(ZoneOffset.UTC);
        return d.withDayOfMonth(1);
    }

    /**
     * Resolve the document type from a type sub-folder in the path ({@code payrolls}, {@code declarations},
     * {@code reports}, …) — checked deepest segment first, since the type folder sits closest to the file.
     * Empty when no segment names a known type (the classifier then decides).
     */
    public static Optional<ro.myfinance.intake.domain.DocumentType> resolveType(RemoteFile file) {
        List<String> segs = segments(file);
        for (int i = segs.size() - 1; i >= 0; i--) {
            Optional<ro.myfinance.intake.domain.DocumentType> t =
                    ro.myfinance.intake.domain.DriveDocLayout.typeOf(segs.get(i));
            if (t.isPresent()) {
                return t;
            }
        }
        return Optional.empty();
    }

    /** A YYYY-MM found anywhere in a string (e.g. a filename "...2026_04.pdf"), as the first of month. */
    public static Optional<LocalDate> periodFromText(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = COMBINED.matcher(text);
        if (m.find()) {
            try {
                return Optional.of(YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))).atDay(1));
            } catch (DateTimeParseException | NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static LocalDate ym(int year, int month) {
        try {
            return YearMonth.of(year, month).atDay(1);
        } catch (DateTimeParseException | NumberFormatException e) {
            return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        }
    }

    /** A month from a folder segment: a leading 1–12 number, else a Romanian month name (short segs only). */
    private static Integer monthOf(String segment) {
        if (segment == null || segment.isBlank()) {
            return null;
        }
        Matcher m = LEAD_MONTH.matcher(segment);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        String norm = StringNormalizer.alnumUpper(segment); // upper, diacritics & non-alnum stripped
        if (norm.length() <= 12) {         // guard: don't match a month name inside a long company name
            for (java.util.Map.Entry<String, Integer> e : RO_MONTHS.entrySet()) {
                if (norm.contains(e.getKey())) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    /** The trimester number (1–4) named in a segment, else null. */
    private static Integer quarterOf(String segment) {
        if (segment == null) {
            return null;
        }
        Matcher m = TRIMESTER.matcher(segment);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static List<String> segments(RemoteFile file) {
        String path = file.path() == null ? "" : file.path();
        return java.util.Arrays.stream(path.split("/")).filter(s -> !s.isBlank()).toList();
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    /** Distinctive core of a legal name (drops SC prefix + legal-form suffix), ≥4 chars, else null. */
    private static String coreName(String name) {
        String n = StringNormalizer.alnumUpper(name);
        n = n.replaceFirst("^SC(?=.{4,}$)", "");
        n = n.replaceFirst("(?<=.{4})(SRLD|SRL|SNC|SCS|SCA|SA|PFA|IF|II)$", "");
        return n.length() >= 4 ? n : null;
    }
}
