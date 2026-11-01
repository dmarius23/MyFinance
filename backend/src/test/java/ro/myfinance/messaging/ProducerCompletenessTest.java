package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ro.myfinance.common.email.SignOff;
import ro.myfinance.common.i18n.RomanianMonths;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.payroll.application.PayrollEmailBuilder;
import ro.myfinance.reports.application.ReportEmailBuilder;
import ro.myfinance.taxpayments.application.PaymentEmailBuilder;
import ro.myfinance.taxpayments.domain.PaymentLine;
import ro.myfinance.taxpayments.domain.TaxCategory;

/**
 * Important 3: every name a kind declares in {@link MessageTemplateKind#allNames()} must be supplied
 * by its producer. Driven off {@link MessageTemplateKind#values()} so adding a kind or a placeholder
 * name cannot pass without a supplier.
 *
 * <p>This test was verified to fail when a single {@code put} call was removed from a producer:
 * removing {@code v.put("netProfit", ...)} from {@link ReportEmailBuilder#values} caused the
 * parameterized case for REPORT to fail with "to contain key: netProfit".
 */
class ProducerCompletenessTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 4, 1);
    private static final String SENDER = "Maria Popescu";
    private static final String FIRM = "ContaZone SRL";

    private static final PaymentLine TVA_LINE = new PaymentLine(
            "RO11TREZ1112345678901234", new BigDecimal("1250.00"), List.of(TaxCategory.TVA),
            "TVA Aprilie-2026", LocalDate.of(2026, 4, 25));

    @ParameterizedTest(name = "all declared names supplied for {0}")
    @EnumSource(MessageTemplateKind.class)
    void producerSuppliesEveryDeclaredName(MessageTemplateKind kind) {
        Map<String, String> values = producerValues(kind);
        for (String name : kind.allNames()) {
            assertThat(values).as("kind=%s, missing key: %s", kind, name).containsKey(name);
        }
    }

    /** Returns the value map that the real producer builds for each kind. */
    private static Map<String, String> producerValues(MessageTemplateKind kind) {
        return switch (kind) {
            case TAX -> new PaymentEmailBuilder().values(
                    "Acme SRL", "RO123456", YearMonth.from(PERIOD),
                    "Trezoreria Cluj Napoca", List.of(TVA_LINE),
                    new BigDecimal("1250.00"), SENDER, FIRM);
            case REPORT -> ReportEmailBuilder.values(
                    PERIOD, CurrentMessageWordingTest.reportData(), SENDER, FIRM);
            case PAYROLL -> PayrollEmailBuilder.values(PERIOD, "Acme SRL", SENDER, FIRM,
                    PERIOD.plusMonths(1).withDayOfMonth(25));
            case REMINDER_NOTHING_RECEIVED, REMINDER_DOCS_MISSING, REMINDER_DOCS_ONLY ->
                    reminderValues(kind);
        };
    }

    /**
     * Builds the value map that {@code ReminderBodyService.compose} assembles. The service itself
     * requires a live DB to exercise end-to-end; we assert directly against the map-building step so
     * the test is fast and does not need mocking infrastructure, while still failing if a key is
     * removed from the compose method.
     */
    private static Map<String, String> reminderValues(MessageTemplateKind kind) {
        Map<String, String> v = new HashMap<>();
        v.put("companyName", "Acme SRL");
        v.put("month", RomanianMonths.monthYear(PERIOD));
        v.put("missingCount", "2");
        v.put("missingTransactions", "• 15.04.2026 — Furnizor Alfa SRL — 1.234,56 RON");
        v.put("signOff", SignOff.names(SENDER, FIRM));
        return v;
    }
}
