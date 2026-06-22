package ro.myfinance.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.reports.application.TrialBalanceExtractor;
import ro.myfinance.reports.domain.TrialBalanceData;

/**
 * Validates trial-balance extraction against a real SAGA balanță de verificare. The PDF carries PII and
 * is gitignored at src/test/resources/fixtures/reports/balanta_2026_03.pdf — the test self-skips when
 * absent so CI stays green without it.
 */
class TrialBalanceExtractorTest {

    private final TrialBalanceExtractor extractor = new TrialBalanceExtractor();

    private TrialBalanceData parseFixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/reports/balanta_2026_03.pdf")) {
            assumeTrue(in != null, "fixture missing: balanta_2026_03.pdf (PII PDF, gitignored)");
            return extractor.extract(in.readAllBytes());
        }
    }

    private BigDecimal of(String s) {
        return new BigDecimal(s);
    }

    private TrialBalanceData.Line line(TrialBalanceData d, String account) {
        return d.lines().stream().filter(l -> l.account().equals(account)).findFirst().orElseThrow();
    }

    @Test
    void parsesHeaderAndPeriod() throws Exception {
        TrialBalanceData d = parseFixture();
        assertThat(d.companyName()).contains("INNOVATECODE IT SRL");
        assertThat(d.cui()).isEqualTo("49443957");
        assertThat(d.periodStart()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(d.periodEnd()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    void crossCheckBalances() throws Exception {
        TrialBalanceData d = parseFixture();
        assertThat(d.balanced()).isTrue();
        assertThat(d.grandTotalDebit()).isEqualByComparingTo(of("178826.04"));
        assertThat(d.grandTotalCredit()).isEqualByComparingTo(of("178826.04"));
    }

    @Test
    void parsesKeyAccounts() throws Exception {
        TrialBalanceData d = parseFixture();
        // Revenue (class 7): period credit movement.
        assertThat(line(d, "704").periodCredit()).isEqualByComparingTo(of("9653.78"));
        // Salaries (class 6): period debit movement.
        assertThat(line(d, "641").periodDebit()).isEqualByComparingTo(of("4060.00"));
        // Depreciation expense.
        assertThat(line(d, "6811").periodDebit()).isEqualByComparingTo(of("2391.89"));
        // Bank closing balance (asset).
        assertThat(line(d, "5121").closingDebit()).isEqualByComparingTo(of("25223.16"));
        // Loans closing balance (liability).
        assertThat(line(d, "167").closingCredit()).isEqualByComparingTo(of("64582.31"));
    }

    @Test
    void derivesProfitFromClass6And7() throws Exception {
        TrialBalanceData d = parseFixture();
        BigDecimal revenue = d.lines().stream().filter(l -> l.accountClass() == 7)
                .map(TrialBalanceData.Line::periodCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Operating expenses exclude the profit-tax account (698), matching SAGA's situație profit.
        BigDecimal opEx = d.lines().stream()
                .filter(l -> l.accountClass() == 6 && !l.account().startsWith("698"))
                .map(TrialBalanceData.Line::periodDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(revenue).isEqualByComparingTo(of("9653.78"));
        assertThat(opEx).isEqualByComparingTo(of("7802.02"));
        assertThat(revenue.subtract(opEx)).isEqualByComparingTo(of("1851.76")); // gross profit
    }
}
