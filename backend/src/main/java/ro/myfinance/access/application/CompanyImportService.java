package ro.myfinance.access.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import ro.myfinance.company.application.CompanyService;
import ro.myfinance.company.domain.Company;
import ro.myfinance.common.web.ConflictException;

/**
 * Bulk-onboards companies from an accountant's CSV. Each row creates a company (validated exactly like the
 * create form) and, if a representative email is present, invites that representative. Import is
 * <b>partial</b>: valid non-duplicate rows are committed independently, invalid/duplicate rows are skipped
 * and reported. Lives in {@code access} because a row spans company creation + representative invite (and
 * {@code access} already depends on {@code company}), avoiding a module cycle.
 */
@Service
public class CompanyImportService {

    private static final Pattern CUI = Pattern.compile("(RO)?[0-9]{2,10}", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    /** Canonical column keys (header cells are normalized to letters-only lowercase before matching). */
    private static final List<String> REQUIRED = List.of("name", "cui", "residence", "vat", "taxregime");

    private final CompanyService companies;
    private final RepresentativeService representatives;

    public CompanyImportService(CompanyService companies, RepresentativeService representatives) {
        this.companies = companies;
        this.representatives = representatives;
    }

    public enum Status { CREATED, SKIPPED, INVALID }

    /** One row's outcome (1-based data-line number, the parsed company name, status, and a reason/detail). */
    public record RowResult(int line, String name, Status status, String message) {}

    public record ImportResult(int total, int created, int skipped, int invalid, List<RowResult> rows) {}

    public ImportResult importCsv(byte[] csv) {
        List<List<String>> rows = parse(new String(csv, StandardCharsets.UTF_8));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The CSV is empty");
        }
        Map<String, Integer> col = header(rows.get(0));
        List<RowResult> out = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.stream().allMatch(String::isBlank)) {
                continue; // ignore blank lines
            }
            out.add(importRow(i, r, col));
        }
        int created = (int) out.stream().filter(x -> x.status() == Status.CREATED).count();
        int skipped = (int) out.stream().filter(x -> x.status() == Status.SKIPPED).count();
        int invalid = (int) out.stream().filter(x -> x.status() == Status.INVALID).count();
        return new ImportResult(out.size(), created, skipped, invalid, out);
    }

    private RowResult importRow(int line, List<String> r, Map<String, Integer> col) {
        String name = cell(r, col, "name");
        String cui = cell(r, col, "cui").toUpperCase(Locale.ROOT);
        String repEmail = cell(r, col, "repemail");
        Company company;
        boolean existed;
        try {
            String residence = cell(r, col, "residence");
            String type = normEntityType(cell(r, col, "type"));
            String vat = normVat(cell(r, col, "vat"));
            String regime = normRegime(cell(r, col, "taxregime"));
            boolean employees = normBool(cell(r, col, "hasemployees"));

            require(!name.isBlank(), "Missing company name");
            require(CUI.matcher(cui).matches(), "Invalid CUI '" + cui + "' (2–10 digits, optional RO prefix)");
            require(!residence.isBlank(), "Missing fiscal residence");
            require(vat != null, "VAT must be platitor/neplatitor (or da/nu)");
            require(regime != null, "Tax regime must be micro or profit");

            company = companies.create(name, cui, type, residence, vat, null, regime, employees, null);
            existed = false;
        } catch (ConflictException dup) {
            // Company already exists. If the row also carries a representative, back-fill it onto the
            // existing company (so re-importing the same CSV attaches reps that a prior import missed);
            // otherwise just skip the duplicate.
            if (repEmail.isBlank()) {
                return new RowResult(line, name, Status.SKIPPED, dup.getMessage());
            }
            company = companies.findByCui(cui).orElse(null);
            if (company == null) {
                return new RowResult(line, name, Status.SKIPPED, dup.getMessage());
            }
            existed = true;
        } catch (RuntimeException e) {
            return new RowResult(line, name, Status.INVALID, e.getMessage());
        }

        Status status = existed ? Status.SKIPPED : Status.CREATED;
        String base = existed ? "Company already existed" : "Company created";

        // Invite the representative if one is given (soft-fail: never lose the company/row over the rep).
        if (repEmail.isBlank()) {
            return new RowResult(line, name, status, existed ? base : null);
        }
        String repName = cell(r, col, "repname");
        if (repName.isBlank() || !EMAIL.matcher(repEmail).matches()) {
            return new RowResult(line, name, status, base + "; representative skipped — invalid name/email");
        }
        // Phone is best-effort: normalized to national form, dropped (null) if unrecognized — a bad phone
        // must never cost us the representative.
        String repPhone = normPhone(cell(r, col, "repphone"));
        try {
            representatives.inviteRepresentative(company.getId(), repName, repEmail, repPhone);
        } catch (RuntimeException e) {
            return new RowResult(line, name, status, base + "; representative — " + e.getMessage());
        }
        return new RowResult(line, name, status, existed ? base + "; representative added" : null);
    }

    /**
     * Normalize a Romanian phone to national {@code 0XXXXXXXXX} form, or null when it can't be recognized.
     * Accepts a {@code +40}/{@code 0040}/{@code 40} country prefix, an existing leading {@code 0}, or a
     * bare 9-digit subscriber number (e.g. {@code 712345678}), with spaces/dashes/dots/parentheses.
     */
    static String normPhone(String raw) {
        if (raw == null) {
            return null;
        }
        String d = raw.replaceAll("[\\s.()\\-/]", "");
        if (d.startsWith("+")) {
            d = d.substring(1);
        }
        if (d.startsWith("0040")) {
            d = d.substring(4);
        } else if (d.startsWith("40")) {
            d = d.substring(2);
        } else if (d.startsWith("0")) {
            d = d.substring(1);
        }
        return d.matches("[0-9]{9}") ? "0" + d : null;
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    // ---- value normalization (lenient, Romanian- and English-friendly) ----

    private static String normEntityType(String s) {
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v; // SRL/SA/PFA/ONG — free-text on the model, kept as typed
    }

    private static String normVat(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "platitor", "plătitor", "da", "yes", "true", "1", "vat_payer", "vatpayer" -> "VAT_PAYER";
            case "neplatitor", "neplătitor", "nu", "no", "false", "0", "non_vat_payer", "nonvatpayer" -> "NON_VAT_PAYER";
            default -> null;
        };
    }

    private static String normRegime(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "micro" -> "MICRO";
            case "profit" -> "PROFIT";
            default -> null;
        };
    }

    private static boolean normBool(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "da", "yes", "true", "1" -> true;
            default -> false;
        };
    }

    // ---- CSV parsing ----

    private Map<String, Integer> header(List<String> headerRow) {
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < headerRow.size(); i++) {
            col.put(normHeader(headerRow.get(i)), i);
        }
        List<String> missing = REQUIRED.stream().filter(k -> !col.containsKey(k)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("CSV is missing required columns: " + String.join(", ", missing));
        }
        return col;
    }

    private static String normHeader(String h) {
        return h.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private static String cell(List<String> row, Map<String, Integer> col, String key) {
        Integer i = col.get(key);
        return (i == null || i >= row.size() || row.get(i) == null) ? "" : row.get(i).trim();
    }

    /** Split CSV text into rows of fields. Delimiter auto-detected (; or ,); double-quoted fields honoured. */
    static List<List<String>> parse(String text) {
        String body = text.startsWith("﻿") ? text.substring(1) : text; // strip BOM
        char delim = detectDelimiter(body);
        List<List<String>> rows = new ArrayList<>();
        List<String> field = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < body.length() && body.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else { inQuotes = false; }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delim) {
                field.add(cur.toString()); cur.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < body.length() && body.charAt(i + 1) == '\n') { i++; }
                field.add(cur.toString()); cur.setLength(0);
                rows.add(field); field = new ArrayList<>();
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0 || !field.isEmpty()) {
            field.add(cur.toString());
            rows.add(field);
        }
        return rows;
    }

    private static char detectDelimiter(String body) {
        int nl = body.indexOf('\n');
        String firstLine = nl < 0 ? body : body.substring(0, nl);
        long semis = firstLine.chars().filter(c -> c == ';').count();
        long commas = firstLine.chars().filter(c -> c == ',').count();
        return semis >= commas ? ';' : ',';
    }
}
