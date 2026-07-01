package ro.myfinance.extraction.adapter.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;

/**
 * ING statements print each transaction as a multi-line block (date, reference, beneficiary, IBAN/CUI,
 * type, then a signed-amount + running-balance line). This mirrors that shape — including an FX purchase
 * (extra "Suma …"/"Rata …" lines) and a page-break header repeat — and asserts the parser reads every
 * transaction, which the generic single-line parser could not (it returned zero).
 */
class IngStatementParserTest {

    private final IngStatementParser parser = new IngStatementParser();

    // Two debits, two credits (incl. one FX purchase), with a page header repeated mid-statement.
    private static final String ING = String.join("\n",
            "Extras de cont",
            "Nr.1 / 31.01.2026",
            "RO98 INGB 0000 9999 0547 3924",
            "MERIC SRL | CUI 20464846",
            "BIC code (SWIFT): INGBROBU",
            "Sold initial:  Total creditari (2):  Total debitari (2): Sold final: Perioada",
            "17,977.65 362.50 -1,109.35 17,230.80   01 - 31.01.2026",
            "Data procesarii Beneficiar / Ordonator Debitari Creditari Sold intermediar",
            "02.01.2026",
            "8054",
            "AFRICANA IMPEX",
            "Cumparare POS",
            "Nr. Card: **** 0436",
            "Data: 31-12-2025 Autorizare: 832488",
            "-170.00 17,807.65",
            "02.01.2026",
            "8055",
            "SILMARIL SOFTWARE S.R.L.",
            "RO42INGB0000999911202782",
            "CUI:43756149",
            "Incasare",
            "MERIC1039",
            "181.50 17,989.15",
            "Extras de cont",              // page-break header repeat (must be ignored)
            "RO98 INGB 0000 9999 0547 3924",
            "  2 \\ 14",
            "03.01.2026",
            "8057",
            "Google Workspace_mericfin",
            "Cumparare POS",
            "Suma: 178,20 EUR",
            "Rata: 5.2713",
            "-939.35 17,049.80",
            "05.01.2026",
            "8062",
            "RUSU O VLAD SEBASTIAN",
            "RO14BRDE130SV78066291300",
            "CUI:25713328",
            "Incasare",
            "181.00 17,230.80");

    @Test
    void parsesEveryTransactionBlock() {
        assertThat(parser.supports(ING)).isTrue();
        ParsedStatement s = parser.parse(ING);

        assertThat(s.transactions()).hasSize(4);
        assertThat(s.accountIban()).isEqualTo("RO98INGB0000999905473924");
        assertThat(s.openingBalance()).isEqualByComparingTo("17977.65");
        assertThat(s.closingBalance()).isEqualByComparingTo("17230.80"); // 4th column, not the period date

        // Cross-check: opening + Σ(signed amounts) == closing == last running balance.
        BigDecimal sum = s.transactions().stream().map(ParsedTransaction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(s.openingBalance().add(sum)).isEqualByComparingTo("17230.80");
    }

    @Test
    void readsSignedAmountsPartnerAndIban() {
        ParsedStatement s = parser.parse(ING);

        ParsedTransaction pos = s.transactions().get(0);
        assertThat(pos.date()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(pos.amount()).isEqualByComparingTo("-170.00");  // debit printed negative
        assertThat(pos.partnerName()).isEqualTo("AFRICANA IMPEX");

        ParsedTransaction incasare = s.transactions().get(1);
        assertThat(incasare.amount()).isEqualByComparingTo("181.50"); // credit positive
        assertThat(incasare.partnerName()).isEqualTo("SILMARIL SOFTWARE S.R.L.");
        assertThat(incasare.partnerIban()).isEqualTo("RO42INGB0000999911202782");

        // FX purchase: the "Suma …"/"Rata …" lines must not be mistaken for the amount+balance line.
        ParsedTransaction fx = s.transactions().get(2);
        assertThat(fx.amount()).isEqualByComparingTo("-939.35");
        assertThat(fx.balanceAfter()).isEqualByComparingTo("17049.80");
    }

    @Test
    void doesNotSupportNonIngText() {
        assertThat(parser.supports("BRD-Net Transactions List Settlement date")).isFalse();
    }
}
