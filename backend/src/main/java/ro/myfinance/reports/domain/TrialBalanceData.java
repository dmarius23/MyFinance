package ro.myfinance.reports.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The parsed content of a Romanian trial balance (balanță de verificare): one line per account with the
 * four column-pairs (opening / period movements / cumulative totals / closing balances), plus the grand
 * totals. Self-validating: total debit must equal total credit. All amounts are non-authoritative until
 * the balanced cross-check passes (see {@link #balanced()}).
 */
public record TrialBalanceData(
        String companyName,
        String cui,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<Line> lines,
        BigDecimal grandTotalDebit,
        BigDecimal grandTotalCredit) {

    /** One account row. Account class = first digit of {@link #account()} (1..7 in the RO chart). */
    public record Line(
            String account,
            String name,
            BigDecimal openingDebit, BigDecimal openingCredit,
            BigDecimal periodDebit, BigDecimal periodCredit,
            BigDecimal totalDebit, BigDecimal totalCredit,
            BigDecimal closingDebit, BigDecimal closingCredit) {

        public int accountClass() {
            return account != null && !account.isEmpty() && Character.isDigit(account.charAt(0))
                    ? account.charAt(0) - '0' : 0;
        }
    }

    /** Debit and credit grand totals agree within a leu — the trial balance is internally consistent. */
    public boolean balanced() {
        return grandTotalDebit != null && grandTotalCredit != null
                && grandTotalDebit.subtract(grandTotalCredit).abs().compareTo(BigDecimal.ONE) < 0;
    }
}
