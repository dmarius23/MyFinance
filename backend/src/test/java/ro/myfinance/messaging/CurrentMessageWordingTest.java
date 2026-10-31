package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.messaging.DefaultMessageTemplates;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.TemplateRenderer;
import ro.myfinance.payroll.application.PayrollEmailBuilder;

/**
 * Pins the exact wording clients receive today, before templates are introduced. These assertions are
 * the regression net for Tasks 4–8: the rendered built-in default must reproduce them byte for byte, so
 * a firm that never customizes sees no change whatsoever.
 */
class CurrentMessageWordingTest {

    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);
    private static final String SENDER = "Maria Popescu";
    private static final String FIRM = "ContaZone SRL";

    // --- payroll ---

    static final String EXPECTED_PAYROLL_BODY = """
            Bună ziua,

            Vă rog frumos să regăsiți atașate statul de plată, fluturașul de salariu și pontajul pentru luna Aprilie 2026.

            Restul de plată de pe statul de plată și fluturaș trebuie virat din contul firmei în contul personal al salariatului.

            Diferența dintre restul de plată și salariul brut reprezintă taxele salariale. Acestea se vor plăti până în data de 25 Aprilie 2026.

            În ceea ce privește sumele care se plătesc către Trezorerie, precum și conturile aferente, vom reveni cu un email separat.

            O zi bună,
            Maria Popescu
            ContaZone SRL""";

    @Test
    void payrollBodyIsUnchanged() {
        assertThat(PayrollEmailBuilder.body(APRIL, SENDER, FIRM)).isEqualTo(EXPECTED_PAYROLL_BODY);
    }

    @Test
    void payrollSubjectIsUnchanged() {
        assertThat(PayrollEmailBuilder.subject(APRIL)).isEqualTo("State de plată — Aprilie 2026");
    }

    @Test
    void payrollSignsWithFirmOnlyWhenSenderUnknown() {
        assertThat(PayrollEmailBuilder.body(APRIL, null, FIRM))
                .endsWith("O zi bună,\nContaZone SRL");
    }

    // --- report ---

    private static ro.myfinance.reports.domain.ReportData reportData() {
        var pl = new ro.myfinance.reports.domain.ReportData.ProfitLoss(
                new java.math.BigDecimal("100000"), java.util.List.of(),
                new java.math.BigDecimal("60000"), java.util.List.of(),
                new java.math.BigDecimal("40000"),
                new java.math.BigDecimal("1000"),
                new java.math.BigDecimal("39000"));
        var bs = new ro.myfinance.reports.domain.ReportData.BalanceSheet(
                java.util.List.of(), java.math.BigDecimal.ZERO,
                java.util.List.of(), java.math.BigDecimal.ZERO,
                java.util.List.of(), java.math.BigDecimal.ZERO);
        var kpis = new ro.myfinance.reports.domain.ReportData.Kpis(
                null, null, null, null, null, null);
        return new ro.myfinance.reports.domain.ReportData("Acme SRL", "RO123456",
                APRIL, APRIL.withDayOfMonth(30), true, pl, bs, kpis);
    }

    static final String EXPECTED_REPORT_BODY = """
            Bună ziua,

            Vă transmitem atașat raportul financiar pentru luna Aprilie 2026.

            Pe scurt:
            • Venituri: 100 000.00 RON
            • Cheltuieli: 61 000.00 RON
            • Profit net: 39 000.00 RON

            Detaliile complete și graficele se găsesc în documentele atașate.

            O zi bună,
            Maria Popescu
            ContaZone SRL""";

    @Test
    void reportBodyIsUnchanged() {
        assertThat(ro.myfinance.reports.application.ReportEmailBuilder
                .body(APRIL, reportData(), SENDER, FIRM))
                .isEqualTo(EXPECTED_REPORT_BODY);
    }

    @Test
    void reportSubjectIsUnchanged() {
        assertThat(ro.myfinance.reports.application.ReportEmailBuilder.subject(APRIL))
                .isEqualTo("Raport financiar — Aprilie 2026");
    }

    // --- tax ---

    static final String EXPECTED_TAX_BODY = """
            Bună ziua,

            Sumele de plată pentru luna Aprilie 2026 aferente firmei Acme SRL sunt următoarele:

            TVA: 1250
            Impozite: 300
            Conturi:

            - 1250 lei in contul RO11TREZ1112345678901234
            - Beneficiar: Trezoreria Cluj Napoca
            - CUI Beneficiar: RO123456
            - Explicație: TVA Aprilie-2026
            - Scadență: 25 aprilie 2026

            - 300 lei in contul RO22TREZ2212345678901234
            - Beneficiar: Trezoreria Cluj Napoca
            - CUI Beneficiar: RO123456
            - Explicație: Impozite Aprilie-2026

            O zi plăcută,
            Maria Popescu
            ContaZone SRL
            """;

    @Test
    void taxBodyIsUnchanged() {
        var lines = java.util.List.of(
                new ro.myfinance.taxpayments.domain.PaymentLine(
                        "RO11TREZ1112345678901234", new java.math.BigDecimal("1250.00"),
                        java.util.List.of(ro.myfinance.taxpayments.domain.TaxCategory.TVA),
                        "TVA Aprilie-2026", LocalDate.of(2026, 4, 25)),
                new ro.myfinance.taxpayments.domain.PaymentLine(
                        "RO22TREZ2212345678901234", new java.math.BigDecimal("300"),
                        java.util.List.of(ro.myfinance.taxpayments.domain.TaxCategory.IMPOZIT),
                        "Impozite Aprilie-2026", null));
        var values = new ro.myfinance.taxpayments.application.PaymentEmailBuilder().values(
                "Acme SRL", "RO123456", java.time.YearMonth.of(2026, 4), "Trezoreria Cluj Napoca",
                lines, SENDER, FIRM);
        String body = new TemplateRenderer().render(
                DefaultMessageTemplates.of(MessageTemplateKind.TAX).body(), values);
        assertThat(body).isEqualTo(EXPECTED_TAX_BODY);
    }
}
