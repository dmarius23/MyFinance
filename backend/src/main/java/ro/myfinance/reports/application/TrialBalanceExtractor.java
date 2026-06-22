package ro.myfinance.reports.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import ro.myfinance.reports.domain.TrialBalanceData;

/**
 * Parses a Romanian trial balance (balanță de verificare) PDF into structured per-account lines plus
 * grand totals. Fully deterministic text parsing (PDFBox) — no OCR/LLM. Amounts use the RO format with a
 * space thousands-separator and a dot decimal ("143 513.10"); each data row ends with 8 numbers
 * (opening D/C, period D/C, totals D/C, closing D/C). Account-name overflow onto a second line is
 * stitched back onto the preceding account. The extracted figures are non-authoritative until the
 * caller confirms {@link TrialBalanceData#balanced()}.
 */
@Component
public class TrialBalanceExtractor {

    /** A RO-formatted amount: 1–3 digits, optional space-separated thousands groups, dot + 2 decimals. */
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,3}(?:[  ]\\d{3})*\\.\\d{2}");
    /** A data row begins with a 3–4 digit account code followed by a letter (the account name). */
    private static final Pattern ACCOUNT_LINE = Pattern.compile("^\\s*(\\d{3,4})\\s+(\\p{L}.*)$");
    private static final Pattern PERIOD = Pattern.compile(
            "(\\d{2}\\.\\d{2}\\.\\d{4})\\s*--\\s*(\\d{2}\\.\\d{2}\\.\\d{4})");
    private static final Pattern CUI = Pattern.compile("c\\.f\\.\\s*(\\d{2,10})", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public TrialBalanceData extract(byte[] pdf) {
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            text = stripper.getText(doc);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to read trial balance PDF", e);
        }
        return parse(text);
    }

    TrialBalanceData parse(String text) {
        String[] rows = text.split("\\r?\\n");
        String companyName = firstNonBlank(rows);
        String cui = find(text, CUI);
        LocalDate start = null;
        LocalDate end = null;
        Matcher pm = PERIOD.matcher(text);
        if (pm.find()) {
            start = LocalDate.parse(pm.group(1), DMY);
            end = LocalDate.parse(pm.group(2), DMY);
        }

        List<TrialBalanceData.Line> lines = new ArrayList<>();
        BigDecimal grandDebit = null;
        BigDecimal grandCredit = null;

        for (String row : rows) {
            List<BigDecimal> nums = amounts(row);
            String trimmed = row.strip();

            if (trimmed.startsWith("Totaluri") && nums.size() >= 8) {
                // Grand totals row — take the closing-balance pair (last two of the 8).
                grandDebit = nums.get(nums.size() - 2);
                grandCredit = nums.get(nums.size() - 1);
                continue;
            }
            if (trimmed.startsWith("Total")) {
                continue; // per-class subtotal — recomputed from lines, not needed
            }

            Matcher am = ACCOUNT_LINE.matcher(row);
            if (am.matches() && nums.size() >= 8) {
                String account = am.group(1);
                List<BigDecimal> v = nums.subList(nums.size() - 8, nums.size());
                String name = accountName(am.group(2));
                lines.add(new TrialBalanceData.Line(account, name,
                        v.get(0), v.get(1), v.get(2), v.get(3), v.get(4), v.get(5), v.get(6), v.get(7)));
            } else if (!lines.isEmpty() && nums.isEmpty() && isNameOverflow(trimmed)) {
                // Continuation line: account name wrapped — append to the previous account's name.
                int last = lines.size() - 1;
                TrialBalanceData.Line p = lines.get(last);
                lines.set(last, new TrialBalanceData.Line(p.account(), (p.name() + " " + trimmed).trim(),
                        p.openingDebit(), p.openingCredit(), p.periodDebit(), p.periodCredit(),
                        p.totalDebit(), p.totalCredit(), p.closingDebit(), p.closingCredit()));
            }
        }
        return new TrialBalanceData(companyName, cui, start, end, lines, grandDebit, grandCredit);
    }

    /** The account name = the text before the first amount on the row. */
    private static String accountName(String afterCode) {
        Matcher m = AMOUNT.matcher(afterCode);
        String name = m.find() ? afterCode.substring(0, m.start()) : afterCode;
        return name.strip().replaceAll("\\s{2,}", " ");
    }

    private static boolean isNameOverflow(String trimmed) {
        // Overflow lines are plain text (no page furniture / headers).
        return !trimmed.isBlank()
                && !trimmed.startsWith("Pagina")
                && !trimmed.startsWith("Balanta")
                && !trimmed.startsWith("Cont")
                && !trimmed.contains("c.f.")
                && Character.isLetter(trimmed.charAt(0));
    }

    private static List<BigDecimal> amounts(String row) {
        List<BigDecimal> out = new ArrayList<>();
        Matcher m = AMOUNT.matcher(row);
        while (m.find()) {
            out.add(new BigDecimal(m.group().replaceAll("[  ]", "")));
        }
        return out;
    }

    private static String firstNonBlank(String[] rows) {
        for (String r : rows) {
            if (r != null && !r.isBlank()) {
                String s = r.strip();
                int cf = s.toLowerCase().indexOf("c.f.");
                return (cf > 0 ? s.substring(0, cf) : s).strip();
            }
        }
        return null;
    }

    private static String find(String text, Pattern p) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
