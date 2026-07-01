package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.adapter.external.GenericRunningBalanceParser;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;

class GenericRunningBalanceParserTest {

    private final GenericRunningBalanceParser parser = new GenericRunningBalanceParser();

    // RO number format (1.234,56), transactions listed oldest-first.
    private static final String RO_OLDEST_FIRST = """
            Extras de cont
            Sold initial 1.000,00
            05/03/2026 Plata furnizor ACME 200,00 800,00
            12/03/2026 Incasare client 350,00 1.150,00
            Sold final 1.150,00
            """;

    // EN number format (1,234.56), transactions listed newest-first.
    private static final String EN_NEWEST_FIRST = """
            Generic Bank Statement
            Opening balance 1,000.00
            12/03/26 incoming client 350.00 1,150.00
            05/03/26 payment ACME 200.00 800.00
            Closing balance 1,150.00
            """;

    @Test
    void alwaysSupportsAsFallback() {
        assertThat(parser.supports("anything at all")).isTrue();
    }

    @Test
    void parsesRoFormatOldestFirst() {
        ParsedStatement s = parser.parse(RO_OLDEST_FIRST);

        assertThat(s.openingBalance()).isEqualByComparingTo("1000.00");
        assertThat(s.closingBalance()).isEqualByComparingTo("1150.00");
        assertThat(s.transactions()).hasSize(2);

        ParsedTransaction t0 = s.transactions().get(0);
        assertThat(t0.date()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(t0.amount()).isEqualByComparingTo("-200.00");
        assertThat(t0.balanceAfter()).isEqualByComparingTo("800.00");

        ParsedTransaction t1 = s.transactions().get(1);
        assertThat(t1.date()).isEqualTo(LocalDate.of(2026, 3, 12));
        assertThat(t1.amount()).isEqualByComparingTo("350.00");
        assertThat(t1.balanceAfter()).isEqualByComparingTo("1150.00");

        assertSelfBalances(s);
    }

    @Test
    void parsesEnFormatNewestFirst() {
        ParsedStatement s = parser.parse(EN_NEWEST_FIRST);

        assertThat(s.openingBalance()).isEqualByComparingTo("1000.00");
        assertThat(s.closingBalance()).isEqualByComparingTo("1150.00");
        assertThat(s.transactions()).hasSize(2);

        ParsedTransaction t0 = s.transactions().get(0);
        assertThat(t0.date()).isEqualTo(LocalDate.of(2026, 3, 12));
        assertThat(t0.amount()).isEqualByComparingTo("350.00");

        ParsedTransaction t1 = s.transactions().get(1);
        assertThat(t1.date()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(t1.amount()).isEqualByComparingTo("-200.00");

        assertSelfBalances(s);
    }

    // A multi-line block layout (no bank-specific parser): date, description and amount+balance on
    // separate lines. The single-line scan finds nothing; the block fallback should recover the rows.
    private static final String BLOCK_LAYOUT = """
            Extras de cont
            Sold initial 1.000,00
            05/03/2026
            Plata furnizor ACME
            ref 8054
            -200,00 800,00
            12/03/2026
            Incasare client BETA
            350,00 1.150,00
            Sold final 1.150,00
            """;

    @Test
    void parsesMultiLineBlockLayoutViaFallback() {
        ParsedStatement s = parser.parse(BLOCK_LAYOUT);

        assertThat(s.transactions()).hasSize(2);
        assertThat(s.transactions().get(0).date()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(s.transactions().get(0).amount()).isEqualByComparingTo("-200.00");
        assertThat(s.transactions().get(1).date()).isEqualTo(LocalDate.of(2026, 3, 12));
        assertThat(s.transactions().get(1).amount()).isEqualByComparingTo("350.00");
        assertSelfBalances(s);
    }

    private void assertSelfBalances(ParsedStatement s) {
        BigDecimal sum = s.transactions().stream()
                .map(ParsedTransaction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(s.openingBalance().add(sum)).isEqualByComparingTo(s.closingBalance());
    }
}
