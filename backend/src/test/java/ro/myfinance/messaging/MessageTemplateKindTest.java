package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ro.myfinance.common.messaging.MessageTemplateKind;

class MessageTemplateKindTest {

    @Test
    void taxDeclaresItsPlaceholdersAndTwoBlocks() {
        assertThat(MessageTemplateKind.TAX.placeholders())
                .containsExactlyInAnyOrder("companyName", "cui", "month", "treasury", "totalToPay");
        assertThat(MessageTemplateKind.TAX.blocks())
                .containsExactlyInAnyOrder("paymentSummary", "paymentAccounts");
    }

    @Test
    void reportDeclaresItsFigures() {
        assertThat(MessageTemplateKind.REPORT.placeholders())
                .containsExactlyInAnyOrder("companyName", "cui", "month", "revenue", "expenses",
                        "grossProfit", "incomeTax", "netProfit");
        assertThat(MessageTemplateKind.REPORT.blocks()).isEmpty();
    }

    @Test
    void payrollDeclaresTheDeadline() {
        assertThat(MessageTemplateKind.PAYROLL.placeholders())
                .containsExactlyInAnyOrder("companyName", "month", "taxDeadline");
        assertThat(MessageTemplateKind.PAYROLL.blocks()).isEmpty();
    }

    @Test
    void onlyTheDocsMissingReminderCarriesTheTransactionBlock() {
        assertThat(MessageTemplateKind.REMINDER_NOTHING_RECEIVED.blocks()).isEmpty();
        assertThat(MessageTemplateKind.REMINDER_DOCS_ONLY.blocks()).isEmpty();
        assertThat(MessageTemplateKind.REMINDER_DOCS_MISSING.blocks())
                .containsExactly("missingTransactions");
        assertThat(MessageTemplateKind.REMINDER_DOCS_MISSING.placeholders())
                .containsExactlyInAnyOrder("companyName", "month", "missingCount");
    }

    @Test
    void everyKindAllowsSignOff() {
        for (MessageTemplateKind k : MessageTemplateKind.values()) {
            assertThat(k.allNames()).contains("signOff");
        }
    }
}
