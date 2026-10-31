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
                "Trezoreria Cluj Napoca", List.of(TVA), "Maria Popescu", "ContaZone SRL");
        assertThat(values).containsKeys("companyName", "cui", "month", "treasury", "totalToPay",
                "paymentSummary", "paymentAccounts", "signOff");
        assertThat(values.get("month")).isEqualTo("Aprilie 2026");
        assertThat(values.get("totalToPay")).isEqualTo("1250");
        assertThat(values.get("signOff")).isEqualTo("Maria Popescu\nContaZone SRL");
    }
}
