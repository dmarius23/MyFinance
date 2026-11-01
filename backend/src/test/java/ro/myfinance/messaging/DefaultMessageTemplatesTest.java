package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.email.SignOff;
import ro.myfinance.common.messaging.DefaultMessageTemplates;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.TemplateRenderer;

/**
 * The golden net: each built-in default, rendered with the same values the old builders used, must equal
 * the wording pinned in {@link CurrentMessageWordingTest}. A firm that never customizes must see no
 * change at all — this is the test that guarantees it.
 */
class DefaultMessageTemplatesTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    private String render(MessageTemplateKind kind, Map<String, String> values) {
        Map<String, String> all = new HashMap<>(values);
        all.put("signOff", SignOff.names("Maria Popescu", "ContaZone SRL"));
        return renderer.render(DefaultMessageTemplates.of(kind).body(), all);
    }

    @Test
    void payrollDefaultMatchesTodaysWording() {
        String body = render(MessageTemplateKind.PAYROLL, Map.of(
                "companyName", "Acme SRL",
                "month", "Aprilie 2026",
                // The 25th of the month AFTER the period: April payroll, May deadline.
                "taxDeadline", "25 Mai 2026"));
        assertThat(body).isEqualTo(CurrentMessageWordingTest.EXPECTED_PAYROLL_BODY);
    }

    @Test
    void reportDefaultMatchesTodaysWording() {
        String body = render(MessageTemplateKind.REPORT, Map.of(
                "companyName", "Acme SRL",
                "cui", "RO123456",
                "month", "Aprilie 2026",
                "revenue", "100 000.00",
                "expenses", "61 000.00",
                "grossProfit", "40 000.00",
                "incomeTax", "1 000.00",
                "netProfit", "39 000.00"));
        assertThat(body).isEqualTo(CurrentMessageWordingTest.EXPECTED_REPORT_BODY);
    }

    @Test
    void taxDefaultMatchesTodaysWording() {
        String summary = "TVA: 1250\nImpozite: 300";
        String accounts = String.join("\n",
                "",
                "- 1250 lei in contul RO11TREZ1112345678901234",
                "- Beneficiar: Trezoreria Cluj Napoca",
                "- CUI Beneficiar: RO123456",
                "- Explicație: TVA Aprilie-2026",
                "- Scadență: 25 aprilie 2026",
                "",
                "- 300 lei in contul RO22TREZ2212345678901234",
                "- Beneficiar: Trezoreria Cluj Napoca",
                "- CUI Beneficiar: RO123456",
                "- Explicație: Impozite Aprilie-2026");
        String body = render(MessageTemplateKind.TAX, Map.of(
                "companyName", "Acme SRL",
                "cui", "RO123456",
                "month", "Aprilie 2026",
                "treasury", "Trezoreria Cluj Napoca",
                "totalToPay", "1550",
                "paymentSummary", summary,
                "paymentAccounts", accounts));
        assertThat(body).isEqualTo(CurrentMessageWordingTest.EXPECTED_TAX_BODY);
    }

    @Test
    void everyKindHasANonBlankDefaultSubjectAndBody() {
        for (MessageTemplateKind kind : MessageTemplateKind.values()) {
            var t = DefaultMessageTemplates.of(kind);
            assertThat(t.subject()).as("subject of %s", kind).isNotBlank();
            assertThat(t.body()).as("body of %s", kind).isNotBlank();
        }
    }

    @Test
    void everyDefaultUsesOnlyNamesItsKindDeclares() {
        var token = java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");
        for (MessageTemplateKind kind : MessageTemplateKind.values()) {
            var t = DefaultMessageTemplates.of(kind);
            var m = token.matcher(t.body() + "\n" + t.subject());
            while (m.find()) {
                assertThat(kind.allNames()).as("%s uses {{%s}}", kind, m.group(1)).contains(m.group(1));
            }
        }
    }

    @Test
    void reminderDefaultsAskForTheRightThing() {
        assertThat(DefaultMessageTemplates.of(MessageTemplateKind.REMINDER_NOTHING_RECEIVED).body())
                .contains("extrasul de cont");
        assertThat(DefaultMessageTemplates.of(MessageTemplateKind.REMINDER_DOCS_ONLY).body())
                .doesNotContain("extrasul de cont")
                .contains("facturile / chitanțele");
        assertThat(DefaultMessageTemplates.of(MessageTemplateKind.REMINDER_DOCS_MISSING).body())
                .contains("{{missingTransactions}}");
    }

    @Test
    void allThreeReminderSubjectsAreTheSame() {
        String expected = "Documente lipsă — {{month}}";
        assertThat(DefaultMessageTemplates.of(MessageTemplateKind.REMINDER_NOTHING_RECEIVED).subject())
                .isEqualTo(expected);
        assertThat(DefaultMessageTemplates.of(MessageTemplateKind.REMINDER_DOCS_MISSING).subject())
                .isEqualTo(expected);
        assertThat(DefaultMessageTemplates.of(MessageTemplateKind.REMINDER_DOCS_ONLY).subject())
                .isEqualTo(expected);
    }
}
