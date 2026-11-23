package ro.myfinance.ingestion.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
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
    // The month may be written without its leading zero — the firm files invoices under "2026_9".
    // A following digit still disqualifies it, so a longer number ("202612345") is not read as a month.
    private static final Pattern COMBINED = Pattern.compile("(20\\d{2})[-_.]?(0?[1-9]|1[0-2])(?!\\d)");
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
        // Folder-path segments first — a company-named folder is a reliable identity (CUI or name).
        for (String seg : segments(file)) {
            for (Company c : companies) {
                if (matchesCompany(seg, c)) {
                    return Optional.of(c.getId());
                }
            }
        }
        // Then the filename — stricter (CUI, or a DISTINCTIVE name only), so a short word that merely
        // appears in a filename (e.g. the accounting firm's own name in "Scan autorizatie Meric.pdf")
        // does not falsely claim an unrelated document for that company.
        String name = file.name();
        if (name != null && !name.isBlank()) {
            for (Company c : companies) {
                if (matchesCompanyInFilename(name, c)) {
                    return Optional.of(c.getId());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Resolve the company for a file on the ACCOUNTING (contabilitate clienți) drive by an EXACT match on
     * the client folder name. That folder is the company's legal name, preceded by the word
     * "Contabilitate" — e.g. {@code Contabilitate INNOVATECODE IT SRL}. The match is case-, diacritic- and
     * whitespace-insensitive but otherwise <b>exact</b> (whole-string, never a substring): a folder for a
     * different company — or a short/common name that merely appears inside an unrelated folder — never
     * claims this company's files. Returns empty when no folder segment exactly names a company, so the
     * caller flags the file for review instead of guessing.
     */
    public static Optional<UUID> resolveAccountingCompany(RemoteFile file, List<Company> companies) {
        for (String seg : segments(file)) {
            // Collect ALL companies this segment could be. Matching ignores the legal-form suffix, so two
            // firms differing only by it ("ALPHA SRL" / "ALPHA SA") would both match — that is ambiguous,
            // and guessing would file documents under the wrong company. Skip the segment instead.
            List<Company> hits = companies.stream().filter(c -> isAccountingCompanyFolder(seg, c)).toList();
            if (hits.size() == 1) {
                return Optional.of(hits.get(0).getId());
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code segment} is company {@code c}'s ACCOUNTING client folder: exactly its legal name,
     * optionally prefixed with "Contabilitate" (case/diacritic/space-insensitive, whole-string). Not a
     * substring test — {@code Contabilitate ALPHA SRL} matches only ALPHA SRL, not ALPHABET SRL.
     */
    public static boolean isAccountingCompanyFolder(String segment, Company c) {
        String name = coreName(c.getLegalName());
        if (name == null) {
            return false;
        }
        String seg = StringNormalizer.alnumUpper(segment == null ? "" : segment);
        // The firm labels each client folder "Contabilitate <name>" (occasionally "<name> - contabilitate"),
        // and writes the name informally — usually WITHOUT the legal form: the folder
        // "Contabilitate PRO ASSET MANAGEMENT" holds "PRO ASSET MANAGEMENT SRL". Drop the label from either
        // end, then compare on the suffix-free core (coreName also strips SRL/SA/PFA/… and a leading SC).
        seg = seg.replaceFirst("^CONTABILITATE", "").replaceFirst("CONTABILITATE$", "");
        String segCore = coreName(seg);
        // Whole-string on the core — still NOT a substring test, so "ALPHA" never matches "ALPHABET".
        return segCore != null && segCore.equals(name);
    }

    /** Company identity from a FILENAME: the embedded CUI, or a distinctive (≥6-char) name core. The length
     *  floor keeps a short name (a firm's own name, a common word) from colliding with unrelated files. */
    private static boolean matchesCompanyInFilename(String fileName, Company c) {
        String cuiDigits = digits(c.getCui());
        if (cuiDigits.length() >= 4 && digits(fileName).contains(cuiDigits)) {
            return true;
        }
        String core = coreName(c.getLegalName());
        return core != null && core.length() >= 6 && StringNormalizer.alnumUpper(fileName).contains(core);
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
        return periodFromPath(file).orElseGet(() -> {
            LocalDate d = file.modifiedTime() != null
                    ? file.modifiedTime().atZone(ZoneOffset.UTC).toLocalDate()
                    : LocalDate.now(ZoneOffset.UTC);
            return d.withDayOfMonth(1);
        });
    }

    /**
     * The period stated by the FOLDER PATH, or empty when the path names no month. Separate from
     * {@link #resolvePeriod} because each client organises their own folders — some keep a year-only
     * folder — and "the path says nothing" must be distinguishable from "the path says January", so the
     * caller can fall back to the document's own content instead of the file's modified date.
     */
    public static Optional<LocalDate> periodFromPath(RemoteFile file) {
        List<String> segs = segments(file);
        // 1) A single segment stating BOTH month and year wins, and is read DEEPEST FIRST — the month
        //    folder sits closest to the file, so "Arhiva 2026/Ianuarie 2027" is January 2027, not 2026.
        //    Pairing them from one segment is the point: harvesting the year and the month independently
        //    (step 2) lets an ancestor folder's year override the month folder's own, which silently files
        //    a whole year's documents under the wrong year once several years sit side by side.
        for (int i = segs.size() - 1; i >= 0; i--) {
            Optional<LocalDate> stated = completePeriodOf(segs.get(i));
            if (stated.isPresent()) {
                return stated;
            }
        }
        // 2) Separate year and month segments — the "<company>/2026/04 Aprilie" layout, where no single
        //    segment names both. Only reached when step 1 found nothing, so there is no year to conflict.
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
            return Optional.of(ym(year, month));
        }
        // 2b) Interim-balance folders carry a trimester, not a calendar month: "Bilant interimar T2 an 2026".
        //     Map the quarter to its end month (T1→Mar, T2→Jun, T3→Sep, T4→Dec).
        if (year != null) {
            for (String s : segs) {
                Integer quarter = quarterOf(s);
                if (quarter != null) {
                    return Optional.of(ym(year, quarter * 3));
                }
            }
        }
        return Optional.empty();
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
        // A Romanian month name beside a year — "BT_august 2026_Extras cont", "Septembrie 2026_Extras".
        Matcher y = YEAR.matcher(text);
        if (y.find()) {
            String upper = StringNormalizer.alnumUpper(text);
            for (java.util.Map.Entry<String, Integer> e : RO_MONTHS.entrySet()) {
                if (upper.contains(e.getKey())) {
                    return Optional.of(ym(Integer.parseInt(y.group(1)), e.getValue()));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The month STATED for a file on the ACCOUNTING drive — the filename first (the firm names statements
     * "BT_august 2026_Extras cont…"), then the folder path. Empty when neither names one: each client
     * organises their own folders and some keep only a year folder, in which case the caller falls back
     * to the document's own content rather than the file's modified month.
     */
    public static Optional<LocalDate> statedAccountingPeriod(RemoteFile file) {
        return periodFromText(file.name()).or(() -> periodFromPath(file));
    }

    private static LocalDate ym(int year, int month) {
        try {
            return YearMonth.of(year, month).atDay(1);
        } catch (DateTimeParseException | NumberFormatException e) {
            return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        }
    }

    /**
     * A COMPLETE period named by one segment on its own: {@code 2026-09} / {@code 202609} / {@code 2026_9},
     * or a month beside its year ({@code Septembrie 2026}, {@code 9. Septembrie 2026}). Empty when the
     * segment names only one of the two — a bare year folder, or a bare month folder.
     */
    private static Optional<LocalDate> completePeriodOf(String segment) {
        Matcher c = COMBINED.matcher(segment);
        if (c.find()) {
            return Optional.of(ym(Integer.parseInt(c.group(1)), Integer.parseInt(c.group(2))));
        }
        Matcher y = YEAR.matcher(segment);
        if (y.find()) {
            Integer mo = monthOf(segment);
            if (mo != null) {
                return Optional.of(ym(Integer.parseInt(y.group(1)), mo));
            }
        }
        return Optional.empty();
    }

    /**
     * A month from a folder segment: a leading 1–12 number ({@code 04 Aprilie}), else a Romanian month
     * NAME as a whole token with nothing left over but its year ({@code Septembrie 2026}, {@code Decembrie}).
     *
     * <p>The whole-token rule is what stops a month word inside a company name from counting. Matching used
     * to be a substring test against the segment with its spaces stripped, bounded by a 12-character guard
     * — which both let {@code MAI SRL} and even {@code MAIN} read as May, and silently rejected the five
     * long month names once a year was appended ({@code SEPTEMBRIE2026} is 14 characters), so those folders
     * fell through to the file's upload month.
     */
    private static Integer monthOf(String segment) {
        if (segment == null || segment.isBlank()) {
            return null;
        }
        Matcher m = LEAD_MONTH.matcher(segment);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        Integer month = null;
        int others = 0;
        for (String token : StringNormalizer.stripDiacritics(segment).toUpperCase(Locale.ROOT)
                .split("[^A-Z0-9]+")) {
            if (token.isEmpty() || YEAR.matcher(token).matches()) {
                continue;                   // the year standing beside the month is expected, not a leftover
            }
            Integer mo = RO_MONTHS.get(token);
            if (mo != null && month == null) {
                month = mo;
            } else {
                others++;
            }
        }
        return others == 0 ? month : null;  // any other word ⇒ a name that merely contains a month word
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
