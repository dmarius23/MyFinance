package ro.myfinance.extraction.adapter.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;

class Mt940StatementParserTest {

    private final Mt940StatementParser parser = new Mt940StatementParser();

    // A standard MT940: account, opening balance, a debit and a credit line each with a :86: info tag,
    // and a closing balance. Amounts use the comma decimal.
    private static final String MT940 = String.join("\n",
            ":20:STMT-1",
            ":25:RO98INGB0000999905473924",
            ":28C:1/1",
            ":60F:C260305RON1000,00",
            ":61:2603050305D200,00NTRFINV-778//BREF001",
            ":86:Plata factura INV-778 ACME FURNIZOR SRL",
            ":61:2603120312C350,00NTRFNONREF//BREF002",
            ":86:Incasare CLIENT BETA SRL RO12BBBB0000000000000001",
            ":62F:C260312RON1150,00");

    @Test
    void supportsMt940() {
        assertThat(parser.supports(MT940)).isTrue();
        assertThat(parser.supports("<Document>camt</Document>")).isFalse();
    }

    @Test
    void parsesBalancesAndSignedLines() {
        ParsedStatement s = parser.parse(MT940);

        assertThat(s.accountIban()).isEqualTo("RO98INGB0000999905473924");
        assertThat(s.openingBalance()).isEqualByComparingTo("1000.00");
        assertThat(s.closingBalance()).isEqualByComparingTo("1150.00");
        assertThat(s.transactions()).hasSize(2);

        ParsedTransaction debit = s.transactions().get(0);
        assertThat(debit.date()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(debit.amount()).isEqualByComparingTo("-200.00"); // D → negative
        assertThat(debit.ref()).isEqualTo("BREF001");
        assertThat(debit.description()).contains("INV-778");

        ParsedTransaction credit = s.transactions().get(1);
        assertThat(credit.amount()).isEqualByComparingTo("350.00"); // C → positive
        assertThat(credit.partnerIban()).isEqualTo("RO12BBBB0000000000000001");

        // Cross-check: opening + Σ == closing.
        BigDecimal sum = s.transactions().stream().map(ParsedTransaction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(s.openingBalance().add(sum)).isEqualByComparingTo("1150.00");
    }
}
