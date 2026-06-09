package ro.myfinance.extraction.adapter.external;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ro.myfinance.extraction.application.BankStatementParser;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;

/**
 * Parser for BRD ("Banca Romana pentru Dezvoltare") statements. BRD prints a per-row running
 * Balance column, so each transaction's signed amount is derived from balance deltas
 * (amount[i] = balance[i] - balance[i+1]; the oldest row vs the opening balance). Amounts use
 * comma-thousands + dot-decimals (e.g. 11,063.29). Transactions are listed newest-first.
 */
@Component
@Order(10)
public class BrdStatementParser implements BankStatementParser {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yy");
    private static final Pattern IBAN_HDR = Pattern.compile("Code IBAN:\\s*(RO\\w+)");
    private static final Pattern BALANCES =
            Pattern.compile("Balance\\s+RON\\s+([\\d.,]+)\\s+Balance\\s+RON\\s+([\\d.,]+)");
    // Transaction header: settlement date + the account's own IBAN + optional description start.
    private static final Pattern TXN_HEADER = Pattern.compile("^(\\d{2}/\\d{2}/\\d{2})\\s+(RO\\w+)\\s*(.*)$");
    // Amount line: a date followed by one/two amounts then the running balance.
    private static final Pattern AMOUNT_LINE = Pattern.compile("^(\\d{2}/\\d{2}/\\d{2})\\s+([\\d.,].*)$");
    private static final Pattern NUMBER = Pattern.compile("[\\d.,]+");

    @Override
    public boolean supports(String text) {
        return text.contains("Banca Romana pentru Dezvoltare")
                || text.contains("BRD-Net")
                || (text.contains("Transactions List") && text.contains("BRDE"));
    }

    private static final Pattern PARTNER_NAME =
            Pattern.compile("Partner name:\\s*(.*?)\\s*Partner account:", Pattern.DOTALL);
    private static final Pattern PARTNER_ACCT =
            Pattern.compile("Partner account:\\s*(.*?)\\s*AccountName:", Pattern.DOTALL);

    @Override
    public ParsedStatement parse(String text) {
        String[] lines = text.split("\\R");
        String accountIban = first(lines, IBAN_HDR, 1);
        BigDecimal opening = null;
        BigDecimal closing = null;
        Matcher bm = BALANCES.matcher(text.replaceAll("\\R", " "));
        if (bm.find()) {
            opening = num(bm.group(1));
            closing = num(bm.group(2));
        }

        // Index every transaction header and amount line, then build one block per transaction.
        List<Integer> headers = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (TXN_HEADER.matcher(lines[i].strip()).matches()) {
                headers.add(i);
            }
        }

        List<Raw> raw = new ArrayList<>();
        for (int hIdx = 0; hIdx < headers.size(); hIdx++) {
            int a = headers.get(hIdx);
            int nextHeader = (hIdx + 1 < headers.size()) ? headers.get(hIdx + 1) : lines.length;
            int amountIdx = -1;
            for (int j = a + 1; j < nextHeader; j++) {
                if (isAmountLine(lines[j])) {
                    amountIdx = j;
                    break;
                }
            }
            if (amountIdx < 0) {
                continue; // header with no amount line — skip (not a real transaction row)
            }
            Matcher h = TXN_HEADER.matcher(lines[a].strip());
            h.matches();
            LocalDate date = LocalDate.parse(h.group(1), DMY);

            StringBuilder block = new StringBuilder(h.group(3));
            for (int j = a + 1; j < amountIdx; j++) {
                block.append(' ').append(lines[j].strip());
            }
            String full = block.toString().strip();

            String partnerName = group(PARTNER_NAME, full);
            String partnerIban = group(PARTNER_ACCT, full);
            int pn = full.indexOf("Partner name:");
            String desc = (pn >= 0 ? full.substring(0, pn) : full).strip();

            raw.add(new Raw(date, desc, blankToNull(partnerName), blankToNull(partnerIban),
                    lastNumber(lines[amountIdx])));
        }

        List<ParsedTransaction> txns = new ArrayList<>();
        for (int k = 0; k < raw.size(); k++) {
            Raw r = raw.get(k);
            BigDecimal prevBalance = (k + 1 < raw.size()) ? raw.get(k + 1).balance : opening;
            BigDecimal signed = (r.balance != null && prevBalance != null)
                    ? r.balance.subtract(prevBalance) : null;
            txns.add(new ParsedTransaction(r.date, signed, r.partnerName, r.partnerIban,
                    blankToNull(r.description), null, r.balance));
        }
        return new ParsedStatement("BRD", accountIban, opening, closing, txns);
    }

    private boolean isAmountLine(String line) {
        return AMOUNT_LINE.matcher(line.strip()).matches();
    }

    private String group(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).strip() : null;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private BigDecimal lastNumber(String line) {
        Matcher m = NUMBER.matcher(line);
        String last = null;
        while (m.find()) {
            last = m.group();
        }
        // skip the leading date token (dd/mm/yy has slashes, won't match NUMBER as a whole)
        return last == null ? null : num(last);
    }

    private BigDecimal num(String s) {
        return new BigDecimal(s.replace(",", ""));
    }

    private String first(String[] lines, Pattern p, int group) {
        for (String l : lines) {
            Matcher m = p.matcher(l);
            if (m.find()) {
                return m.group(group);
            }
        }
        return null;
    }

    private record Raw(LocalDate date, String description, String partnerName, String partnerIban,
                       BigDecimal balance) {
    }
}
