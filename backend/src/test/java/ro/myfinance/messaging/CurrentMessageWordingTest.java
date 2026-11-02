package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.messaging.DefaultMessageTemplates;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.TemplateRenderer;

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

    /**
     * NOTE — the one deliberate wording change on this branch. The salary-tax deadline below reads
     * "25 Mai 2026" for the April payroll, not "25 Aprilie 2026" as it did before. The old text named
     * the period's own month, which was wrong (D112 for April salaries is due 25 May) and contradicted
     * both {@code AnafDeclarationExtractor.defaultDeadline} and {@code DashboardService}. Changed on the
     * user's explicit instruction; every other byte of this constant is still the pre-branch wording.
     */
    static final String EXPECTED_PAYROLL_BODY = """
            Bună ziua,

            Vă rog frumos să regăsiți atașate statul de plată, fluturașul de salariu și pontajul pentru luna Aprilie 2026.

            Restul de plată de pe statul de plată și fluturaș trebuie virat din contul firmei în contul personal al salariatului.

            Diferența dintre restul de plată și salariul brut reprezintă taxele salariale. Acestea se vor plăti până în data de 25 Mai 2026.

            În ceea ce privește sumele care se plătesc către Trezorerie, precum și conturile aferente, vom reveni cu un email separat.

            O zi bună,
            Maria Popescu
            ContaZone SRL""";

    /**
     * The deadline the seeded reference row yields for an April period: day 25 of the following month.
     * Supplied explicitly so this golden test stays deterministic without a database.
     */
    private static final LocalDate APRIL_DEADLINE = LocalDate.of(2026, 5, 25);

    private static String renderPayroll(String sender) {
        var values = ro.myfinance.payroll.application.PayrollEmailBuilder
                .values(APRIL, "Acme SRL", sender, FIRM, APRIL_DEADLINE);
        return new TemplateRenderer().render(
                DefaultMessageTemplates.of(MessageTemplateKind.PAYROLL).body(), values);
    }

    @Test
    void payrollBodyIsUnchanged() {
        assertThat(renderPayroll(SENDER)).isEqualTo(EXPECTED_PAYROLL_BODY);
    }

    @Test
    void payrollSubjectIsUnchanged() {
        var values = ro.myfinance.payroll.application.PayrollEmailBuilder
                .values(APRIL, "Acme SRL", SENDER, FIRM, APRIL_DEADLINE);
        assertThat(new TemplateRenderer().render(
                DefaultMessageTemplates.of(MessageTemplateKind.PAYROLL).subject(), values))
                .isEqualTo("State de plată — Aprilie 2026");
    }

    @Test
    void payrollSignsWithFirmOnlyWhenSenderUnknown() {
        assertThat(renderPayroll(null)).endsWith("O zi bună,\nContaZone SRL");
    }

    // --- report ---

    static ro.myfinance.reports.domain.ReportData reportData() {
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
        var values = ro.myfinance.reports.application.ReportEmailBuilder
                .values(APRIL, reportData(), SENDER, FIRM);
        assertThat(new TemplateRenderer().render(
                DefaultMessageTemplates.of(MessageTemplateKind.REPORT).body(), values))
                .isEqualTo(EXPECTED_REPORT_BODY);
    }

    @Test
    void reportSubjectIsUnchanged() {
        var values = ro.myfinance.reports.application.ReportEmailBuilder
                .values(APRIL, reportData(), SENDER, FIRM);
        assertThat(new TemplateRenderer().render(
                DefaultMessageTemplates.of(MessageTemplateKind.REPORT).subject(), values))
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
                lines, new java.math.BigDecimal("1550"), SENDER, FIRM);
        String body = new TemplateRenderer().render(
                DefaultMessageTemplates.of(MessageTemplateKind.TAX).body(), values);
        assertThat(body).isEqualTo(EXPECTED_TAX_BODY);
    }
}
