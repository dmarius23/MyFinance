package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.adapter.external.BrdStatementParser;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;

/**
 * Unit test for the BRD parser using a redacted, fake statement that reproduces the real BRD
 * PDFBox text layout (no PII). Two transactions whose balances chain to opening/closing, so the
 * balance-delta amount derivation and direction can be asserted exactly.
 */
class BrdStatementParserTest {

    // Mirrors PDFBox's extraction of a real BRD statement, with fake holder/IBANs/amounts.
    private static final String FIXTURE = """
            From - To: 01/03/26 - 31/03/26
            Holder: Demo Test Srl
            Code IBAN: RO00BRDETEST0000000000001
            Currency: RON
            BRD-Net User
            Start balance on 01/03/26 End balance on 31/03/26
            Balance RON 1,000.00 Balance RON 1,150.00
            Transactions List
            Settlement
            date
            Account number Transaction
            description
            Additional information Value
            date
            Debit Credit Balance
            15/03/26 RO00BRDETEST0000000000001 plata factura
            Martie
            Partner name:
            ACME SRL
            Partner account:
            RO00ACME00000000000000001
            AccountName:
            RON
            15/03/26 200.00  1,150.00
            10/03/26 RO00BRDETEST0000000000001  Partner name:
            CLIENT SRL
            Partner account:
            RO00CLIENT0000000000000001
            AccountName:
            RON
            10/03/26 350.00  1,350.00
            """;

    // BRD's "Transactions List" export puts the value date + amount + balance on the SAME line as the
    // settlement date + IBAN + description (fake holder/IBANs/amounts). Two chaining transactions.
    private static final String SINGLE_LINE_FIXTURE = """
            From - To: 01/04/26 - 30/04/26
            Holder: Demo Test Srl
            Code IBAN: RO00BRDETEST0000000000001
            Currency: RON
            BRD-Net User
            Start balance on 01/04/26 End balance on 30/04/26
            Balance RON 1,000.00 Balance RON 1,150.00
            Transactions List
            Settlement Account number Transaction Additional information Value Debit Credit Balance
            date description date
            15/04/26 RO00BRDETEST0000000000001 incasare Partner name: 15/04/26  200.00 1,150.00
            CLIENT SRL
            Partner account:
            RO00CLIENT0000000000000001
            AccountName:
            RON
            10/04/26 RO00BRDETEST0000000000001 plata Partner name: 10/04/26 50.00  950.00
            ACME SRL
            Partner account:
            RO00ACME00000000000000001
            AccountName:
            RON
            """;

    private final BrdStatementParser parser = new BrdStatementParser();

    @Test
    void parsesSingleLineTransactionsListLayout() {
        ParsedStatement s = parser.parse(SINGLE_LINE_FIXTURE);
        assertThat(s.transactions()).hasSize(2);

        ParsedTransaction credit = s.transactions().get(0); // newest first
        assertThat(credit.date()).isEqualTo(LocalDate.of(2026, 4, 15));
        assertThat(credit.amount()).isEqualByComparingTo("200.00");   // 1,150.00 − 950.00
        assertThat(credit.balanceAfter()).isEqualByComparingTo("1150.00");
        assertThat(credit.partnerName()).isEqualTo("CLIENT SRL");
        assertThat(credit.description()).isEqualTo("incasare");

        ParsedTransaction debit = s.transactions().get(1);
        assertThat(debit.date()).isEqualTo(LocalDate.of(2026, 4, 10));
        assertThat(debit.amount()).isEqualByComparingTo("-50.00");    // 950.00 − opening 1,000.00
        assertThat(debit.partnerName()).isEqualTo("ACME SRL");

        java.math.BigDecimal sum = s.transactions().stream()
                .map(ParsedTransaction::amount).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        assertThat(s.openingBalance().add(sum)).isEqualByComparingTo(s.closingBalance());
    }

    @Test
    void supportsBrdText() {
        assertThat(parser.supports(FIXTURE)).isTrue();
        assertThat(parser.supports("BANCA TRANSILVANIA EXTRAS CONT")).isFalse();
    }

    @Test
    void parsesHeaderBalancesAndTransactions() {
        ParsedStatement s = parser.parse(FIXTURE);

        assertThat(s.bankCode()).isEqualTo("BRD");
        assertThat(s.accountIban()).isEqualTo("RO00BRDETEST0000000000001");
        assertThat(s.openingBalance()).isEqualByComparingTo("1000.00");
        assertThat(s.closingBalance()).isEqualByComparingTo("1150.00");
        assertThat(s.transactions()).hasSize(2);

        ParsedTransaction debit = s.transactions().get(0);
        assertThat(debit.date()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(debit.amount()).isEqualByComparingTo("-200.00");   // derived from balance delta
        assertThat(debit.balanceAfter()).isEqualByComparingTo("1150.00");
        assertThat(debit.partnerName()).isEqualTo("ACME SRL");
        assertThat(debit.partnerIban()).isEqualTo("RO00ACME00000000000000001");
        assertThat(debit.description()).isEqualTo("plata factura Martie");

        ParsedTransaction credit = s.transactions().get(1);
        assertThat(credit.date()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(credit.amount()).isEqualByComparingTo("350.00");   // oldest row vs opening
        assertThat(credit.partnerName()).isEqualTo("CLIENT SRL");
        assertThat(credit.description()).isNull();                    // header trailing "Partner name:"

        // The statement self-balances: opening + sum(amounts) == closing.
        java.math.BigDecimal sum = s.transactions().stream()
                .map(ParsedTransaction::amount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        assertThat(s.openingBalance().add(sum)).isEqualByComparingTo(s.closingBalance());
    }
}
