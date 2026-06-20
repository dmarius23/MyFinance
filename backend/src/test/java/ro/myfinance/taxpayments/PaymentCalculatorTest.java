package ro.myfinance.taxpayments;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ro.myfinance.taxpayments.application.PaymentCalculator;
import ro.myfinance.taxpayments.application.PaymentEmailBuilder;
import ro.myfinance.taxpayments.domain.DeclarationType;
import ro.myfinance.taxpayments.domain.ParsedDeclaration;
import ro.myfinance.taxpayments.domain.PaymentLine;
import ro.myfinance.taxpayments.domain.TaxCategory;
import ro.myfinance.taxpayments.domain.TaxObligation;

/** Grouping + email rendering, reproducing the accountant's example email from the D112 obligations. */
class PaymentCalculatorTest {

    private static final String CONT_UNIC = "RO14TREZ2165503XXXXXXXXX";
    private static final String CAM_IBAN = "RO54TREZ21620A470300XXXX";

    private final PaymentCalculator calc = new PaymentCalculator();
    private final PaymentEmailBuilder email = new PaymentEmailBuilder();

    /** The D112 sample obligations (March 2026) with the standard 25-Apr deadline. */
    private ParsedDeclaration d112() {
        LocalDate due = LocalDate.of(2026, 4, 25);
        return new ParsedDeclaration(DeclarationType.D112, "49443957", "INNOVATECODE IT SRL",
                YearMonth.of(2026, 3),
                List.of(
                    new TaxObligation(TaxCategory.IMPOZIT, "602", new BigDecimal("264"), due),
                    new TaxObligation(TaxCategory.CAS, "412", new BigDecimal("1015"), due),
                    new TaxObligation(TaxCategory.CASS, "432", new BigDecimal("406"), due),
                    new TaxObligation(TaxCategory.CAM, "480", new BigDecimal("91"), due)),
                new BigDecimal("1776"));
    }

    /** impozit/CAS/CASS share the cont-unic IBAN → one line of 1685; CAM its own IBAN → 91. */
    private Map<TaxCategory, String> ibans() {
        return Map.of(
            TaxCategory.IMPOZIT, CONT_UNIC,
            TaxCategory.CAS, CONT_UNIC,
            TaxCategory.CASS, CONT_UNIC,
            TaxCategory.CAM, CAM_IBAN,
            TaxCategory.TVA, "RO00TVA");
    }

    @Test
    void groupsByIbanIntoTwoLines() {
        List<PaymentLine> lines = calc.compute(List.of(d112()), ibans());
        assertThat(lines).hasSize(2);

        PaymentLine social = lines.stream().filter(l -> l.iban().equals(CONT_UNIC)).findFirst().orElseThrow();
        assertThat(social.amount()).isEqualByComparingTo("1685");
        assertThat(social.explanation()).isEqualTo("Contribuții sociale Martie-2026");
        assertThat(social.scadenta()).isEqualTo(LocalDate.of(2026, 4, 25));

        PaymentLine cam = lines.stream().filter(l -> l.iban().equals(CAM_IBAN)).findFirst().orElseThrow();
        assertThat(cam.amount()).isEqualByComparingTo("91");
        assertThat(cam.explanation()).isEqualTo("CAM luna Martie-2026");
    }

    @Test
    void emailBodyContainsAmountsAccountsAndBeneficiary() {
        List<PaymentLine> lines = calc.compute(List.of(d112()), ibans());
        String body = email.build("INNOVATECODE IT SRL", "49443957", YearMonth.of(2026, 3),
                "Trezoreria Cluj Napoca", lines);

        assertThat(body).contains("firmei INNOVATECODE IT SRL");
        assertThat(body).contains("Contribuții sociale: 1685");
        assertThat(body).contains("CAM: 91");
        assertThat(body).contains("1685 lei in contul " + CONT_UNIC);
        assertThat(body).contains("91 lei in contul " + CAM_IBAN);
        assertThat(body).contains("CUI Beneficiar: 49443957");
        assertThat(body).contains("Trezoreria Cluj Napoca");
    }

    @Test
    void separateIbansAreNotMerged() {
        // If each category has its own IBAN, the social block splits into three lines + CAM.
        Map<TaxCategory, String> perTax = Map.of(
            TaxCategory.IMPOZIT, "RO-IMP", TaxCategory.CAS, "RO-CAS",
            TaxCategory.CASS, "RO-CASS", TaxCategory.CAM, CAM_IBAN, TaxCategory.TVA, "RO-TVA");
        List<PaymentLine> lines = calc.compute(List.of(d112()), perTax);
        assertThat(lines).hasSize(4);
    }
}
