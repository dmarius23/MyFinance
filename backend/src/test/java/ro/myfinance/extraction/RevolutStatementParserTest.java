package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.adapter.external.RevolutStatementParser;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;

/**
 * Unit test for the Revolut parser using a redacted, fake statement that reproduces the real Revolut
 * PDFBox text layout (no PII). It contains two currency sub-statements — an AUD one that must be
 * ignored and a RON one that must be parsed — so the currency filter and the balance-chain sign
 * derivation (Revolut lists newest-first) are both asserted exactly.
 */
class RevolutStatementParserTest {

    // Mirrors PDFBox's extraction of a real Revolut combined statement, with fake holder/IBANs/amounts.
    // The RON table lists newest-first: the MOS (money out) row precedes the EXI (money in) row even
    // though EXI happened earlier — exactly the ordering the parser reverses to chain balances.
    private static final String FIXTURE = """
            Revolut Bank UAB
            Monthly statement
            Currency        AUD
            Opening balance        10.00 AUD
            Closing balance        10.00 AUD
            Money out      Money in
            Transactions from 1 Dec 2024 to 31 Dec 2024
            2 Dec 2024   EXO   AUD payment   5.00 AUD   5.00 AUD
            Transaction types
            EXO - Exchange out
            Monthly statement
            Account details
            Type        Local
            IBAN        RO04 BREL0000000000000001
            BIC         BRELROBU
            Currency        RON
            Opening balance        0.00 RON
            Closing balance        0.00 RON
            Date      Description      Money out      Money in      Balance
            Transactions from 1 Dec 2024 to 31 Dec 2024
            4 Dec 2024   MOS   To Codesio Software SRL • Transfer   44 491.82 RON   0.00 RON
            Reference: 674ff577-aaaa-bbbb
            To account: RO68BTRLRONCRT0CH3184101
            1 Dec 2024   EXI   Money added via bank transfer   44 491.82 RON   44 491.82 RON
            Reference: 111aaa
            Transaction types
            MOS - Money sent
            EXI - Exchange in
            """;

    private final RevolutStatementParser parser = new RevolutStatementParser();

    @Test
    void supportsRevolutTextOnly() {
        assertThat(parser.supports(FIXTURE)).isTrue();
        assertThat(parser.supports("BRD-Net User Transactions List Debit Credit Balance")).isFalse();
    }

    @Test
    void parsesOnlyTheRonSectionAndDerivesSignsFromTheBalanceChain() {
        ParsedStatement s = parser.parse(FIXTURE);

        assertThat(s.bankCode()).isEqualTo("REVOLUT");
        assertThat(s.accountIban()).isEqualTo("RO04BREL0000000000000001");
        assertThat(s.openingBalance()).isEqualByComparingTo("0.00");
        assertThat(s.closingBalance()).isEqualByComparingTo("0.00");

        // The AUD sub-statement is dropped entirely — only the two RON transactions remain.
        assertThat(s.transactions()).hasSize(2);

        // Sorted oldest-first: the Dec 1 credit, then the Dec 4 debit.
        ParsedTransaction credit = s.transactions().get(0);
        assertThat(credit.date()).isEqualTo(LocalDate.of(2024, 12, 1));
        assertThat(credit.amount()).isEqualByComparingTo("44491.82");     // balance 44491.82 - opening 0
        assertThat(credit.balanceAfter()).isEqualByComparingTo("44491.82");

        ParsedTransaction debit = s.transactions().get(1);
        assertThat(debit.date()).isEqualTo(LocalDate.of(2024, 12, 4));
        assertThat(debit.amount()).isEqualByComparingTo("-44491.82");     // balance 0 - 44491.82
        assertThat(debit.balanceAfter()).isEqualByComparingTo("0.00");
        assertThat(debit.partnerName()).isEqualTo("Codesio Software SRL");
        assertThat(debit.partnerIban()).isEqualTo("RO68BTRLRONCRT0CH3184101");
        assertThat(debit.description()).contains("Codesio Software SRL");

        // The RON statement self-balances: opening + sum(amounts) == closing.
        BigDecimal sum = s.transactions().stream()
                .map(ParsedTransaction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(s.openingBalance().add(sum)).isEqualByComparingTo(s.closingBalance());
    }
}
