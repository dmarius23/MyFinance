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
    private static final Pattern PHONE = Pattern.compile("(\\+40|0040|0)[0-9]{9}");
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
        Company company;
        try {
            String cui = cell(r, col, "cui").toUpperCase(Locale.ROOT);
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
        } catch (ConflictException dup) {
            return new RowResult(line, name, Status.SKIPPED, dup.getMessage());
        } catch (RuntimeException e) {
            return new RowResult(line, name, Status.INVALID, e.getMessage());
        }
        // Company created — invite the representative if one is given (soft-fail: keep the company).
        String repEmail = cell(r, col, "repemail");
        if (!repEmail.isBlank()) {
            String repName = cell(r, col, "repname");
            String repPhone = cell(r, col, "repphone");
            if (repName.isBlank() || !EMAIL.matcher(repEmail).matches()
                    || (!repPhone.isBlank() && !PHONE.matcher(repPhone).matches())) {
                return new RowResult(line, name, Status.CREATED, "Company created; representative skipped — invalid name/email/phone");
            }
            try {
                representatives.inviteRepresentative(company.getId(), repName, repEmail, repPhone.isBlank() ? null : repPhone);
            } catch (RuntimeException e) {
                return new RowResult(line, name, Status.CREATED, "Company created; representative not invited — " + e.getMessage());
            }
        }
        return new RowResult(line, name, Status.CREATED, null);
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
