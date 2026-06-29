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
import ro.myfinance.company.domain.Company;
import ro.myfinance.ingestion.application.CloudFolderConnector.RemoteFile;

/**
 * Pure mapping rules for the agreed layout {@code <root>/<company name or CUI>/[YYYY-MM]/files}:
 * resolve the client company from the first path segment (matched by CUI digits or a distinctive part
 * of the legal name) and the period from a {@code YYYY-MM} segment (else the file's modified month).
 * No I/O — fully unit-testable.
 */
public final class FolderMapper {

    private static final Pattern MONTH = Pattern.compile("(20\\d{2})[-_.]?(0[1-9]|1[0-2])");

    private FolderMapper() {
    }

    /** Resolve the company a file belongs to from its folder path, against the tenant's companies. */
    public static Optional<UUID> resolveCompany(RemoteFile file, List<Company> companies) {
        for (String segment : segments(file)) {
            String segDigits = digits(segment);
            String segNorm = normalize(segment);
            for (Company c : companies) {
                String cuiDigits = digits(c.getCui());
                if (cuiDigits.length() >= 4 && segDigits.contains(cuiDigits)) {
                    return Optional.of(c.getId());
                }
                String core = coreName(c.getLegalName());
                if (core != null && segNorm.contains(core)) {
                    return Optional.of(c.getId());
                }
            }
        }
        return Optional.empty();
    }

    /** Resolve the period (first of month): a YYYY-MM path segment, else the file's modified month. */
    public static LocalDate resolvePeriod(RemoteFile file) {
        for (String segment : segments(file)) {
            Matcher m = MONTH.matcher(segment);
            if (m.find()) {
                try {
                    return YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))).atDay(1);
                } catch (DateTimeParseException | NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        LocalDate d = file.modifiedTime() != null
                ? file.modifiedTime().atZone(ZoneOffset.UTC).toLocalDate()
                : LocalDate.now(ZoneOffset.UTC);
        return d.withDayOfMonth(1);
    }

    private static List<String> segments(RemoteFile file) {
        String path = file.path() == null ? "" : file.path();
        return java.util.Arrays.stream(path.split("/")).filter(s -> !s.isBlank()).toList();
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String stripped = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('ș', 's').replace('ț', 't');
        return stripped.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    /** Distinctive core of a legal name (drops SC prefix + legal-form suffix), ≥4 chars, else null. */
    private static String coreName(String name) {
        String n = normalize(name);
        n = n.replaceFirst("^SC(?=.{4,}$)", "");
        n = n.replaceFirst("(?<=.{4})(SRLD|SRL|SNC|SCS|SCA|SA|PFA|IF|II)$", "");
        return n.length() >= 4 ? n : null;
    }
}
