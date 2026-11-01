package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import ro.myfinance.taxpayments.application.PaymentEmailBuilder;
import ro.myfinance.taxpayments.domain.PaymentLine;
import ro.myfinance.taxpayments.domain.TaxCategory;

/**
 * The tax builder's remaining job: render the two blocks and hand the renderer a variable map. The
 * blocks stay system-owned so a tenant can reposition the account list but never reformat an IBAN line.
 */
class TaxTemplateWiringTest {

    private final PaymentEmailBuilder builder = new PaymentEmailBuilder();

    private static final PaymentLine TVA = new PaymentLine(
            "RO11TREZ1112345678901234", new BigDecimal("1250.00"), List.of(TaxCategory.TVA),
            "TVA Aprilie-2026", LocalDate.of(2026, 4, 25));

    @Test
    void summaryBlockIsOneLabelledAmountPerLineWithoutTrailingNewline() {
        assertThat(builder.summaryBlock(List.of(TVA))).isEqualTo("TVA: 1250");
    }

    @Test
    void accountsBlockStartsEachGroupWithABlankLine() {
        String out = builder.accountsBlock(List.of(TVA), "Trezoreria Cluj Napoca", "RO123456");
        assertThat(out).isEqualTo(String.join("\n",
                "",
                "- 1250 lei in contul RO11TREZ1112345678901234",
                "- Beneficiar: Trezoreria Cluj Napoca",
                "- CUI Beneficiar: RO123456",
                "- Explicație: TVA Aprilie-2026",
                "- Scadență: 25 aprilie 2026"));
    }

    @Test
    void accountsBlockOmitsTheDueDateLineWhenThereIsNone() {
        PaymentLine noDue = new PaymentLine("RO22TREZ2212345678901234", new BigDecimal("300"),
                List.of(TaxCategory.TVA), "TVA Aprilie-2026", null);
        assertThat(builder.accountsBlock(List.of(noDue), "Trezoreria Cluj Napoca", "RO123456"))
                .doesNotContain("Scadență");
    }

    @Test
    void valuesCarryEveryNameTheTaxKindDeclares() {
        var values = builder.values("Acme SRL", "RO123456", YearMonth.of(2026, 4),
                "Trezoreria Cluj Napoca", List.of(TVA), new BigDecimal("1250.00"),
                "Maria Popescu", "ContaZone SRL");
        assertThat(values).containsKeys("companyName", "cui", "month", "treasury", "totalToPay",
                "paymentSummary", "paymentAccounts", "signOff");
        assertThat(values.get("month")).isEqualTo("Aprilie 2026");
        assertThat(values.get("totalToPay")).isEqualTo("1250");
        assertThat(values.get("signOff")).isEqualTo("Maria Popescu\nContaZone SRL");
    }

    @Test
    void accountsBlockNullGuardsPreventLiteralNullInBeneficiaryAndCuiLines() {
        String out = builder.accountsBlock(List.of(TVA), null, null);
        assertThat(out).contains("- Beneficiar: ");
        assertThat(out).contains("- CUI Beneficiar: ");
        assertThat(out).doesNotContain("null");
    }

    @Test
    void accountsBlockBlankGuardsPreventTrailingWhitespaceInBeneficiaryAndCuiLines() {
        String out = builder.accountsBlock(List.of(TVA), "   ", "\t\n");
        assertThat(out).contains("- Beneficiar: \n");
        assertThat(out).contains("- CUI Beneficiar: \n");
        assertThat(out).doesNotContain("   ");
    }

    @Test
    void valuesRendersBlankInputsAsEmpty() {
        var values = builder.values("   ", "  \t  ", YearMonth.of(2026, 4),
                "\n", List.of(TVA), new BigDecimal("1250.00"), "Maria Popescu", "ContaZone SRL");
        assertThat(values.get("companyName")).isEmpty();
        assertThat(values.get("cui")).isEmpty();
        assertThat(values.get("treasury")).isEmpty();
    }

    /**
     * Important 2: {{totalToPay}} must equal the authoritative Computation.total, not just the sum of
     * the configured lines. When one category has no treasury IBAN its amount is excluded from the
     * configured lines but must still appear in totalToPay — otherwise a TAX template would understate
     * the state liability while the in-app notification shows the correct (larger) figure.
     */
    @Test
    void totalToPayUsesAuthoritativeTotalNotLineSum() {
        PaymentLine configured = new PaymentLine(
                "RO11TREZ1112345678901234", new BigDecimal("1250.00"), List.of(TaxCategory.TVA),
                "TVA Aprilie-2026", LocalDate.of(2026, 4, 25));
        // unconfigured category contributes to the authoritative total but is NOT in the lines list
        BigDecimal authoritativeTotal = new BigDecimal("1550.00"); // 1250 configured + 300 unconfigured
        var values = builder.values("Acme SRL", "RO123456", YearMonth.of(2026, 4),
                "Trezoreria Cluj Napoca", List.of(configured), authoritativeTotal,
                "Maria Popescu", "ContaZone SRL");
        // totalToPay must reflect the authoritative total (1550), not the sum of lines (1250)
        assertThat(values.get("totalToPay")).isEqualTo("1550");
        // the configured lines only appear in blocks — summary covers the configured portion
        assertThat(values.get("paymentSummary")).isEqualTo("TVA: 1250");
    }
}
