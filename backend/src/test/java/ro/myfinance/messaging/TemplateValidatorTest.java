package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.settings.application.TemplateValidator;

/**
 * Save-time validation is the whole point of the placeholder contract: a typo must fail here, in
 * Settings, rather than reach a client as literal {{compnayName}}.
 */
class TemplateValidatorTest {

    private final TemplateValidator validator = new TemplateValidator();

    @Test
    void acceptsATemplateUsingOnlyDeclaredNames() {
        assertThatCode(() -> validator.validate(MessageTemplateKind.PAYROLL,
                "Salarii {{month}}",
                "Bună ziua,\n\nLuna {{month}}, scadența {{taxDeadline}}.\n\nMulțumim,\n{{signOff}}"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAMisspelledPlaceholder() {
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.REPORT,
                "Raport {{month}}", "Firma {{compnayName}} a avut {{revenue}}."))
                .hasMessageContaining("compnayName");
    }

    @Test
    void rejectsAPlaceholderBelongingToAnotherKind() {
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.PAYROLL,
                "Salarii", "Conturi: {{paymentAccounts}}"))
                .hasMessageContaining("paymentAccounts");
    }

    @Test
    void rejectsABlockInsideASubject() {
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.TAX,
                "Sume {{paymentSummary}}", "Bună ziua, {{signOff}}"))
                .hasMessageContaining("paymentSummary");
    }

    @Test
    void rejectsUnbalancedDelimiters() {
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.PAYROLL,
                "Salarii", "Luna {{month}."))
                .hasMessageContaining("{{");
    }

    @Test
    void rejectsABlankBody() {
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.PAYROLL, "Salarii", "   "))
                .hasMessageContaining("body");
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.PAYROLL, "Salarii", null))
                .hasMessageContaining("body");
    }

    @Test
    void rejectsAnOversizedBodyOrSubject() {
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.PAYROLL, "Salarii",
                "x".repeat(10_001))).hasMessageContaining("10000");
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.PAYROLL,
                "s".repeat(301), "Bună ziua")).hasMessageContaining("300");
    }

    /** A blank subject is allowed — it means "keep the built-in subject". */
    @Test
    void allowsABlankSubject() {
        assertThatCode(() -> validator.validate(MessageTemplateKind.PAYROLL, "  ", "Bună ziua"))
                .doesNotThrowAnyException();
    }

    /** Review Focus 2, save half: CRLF must not count against the length cap or reach storage. */
    @Test
    void acceptsCrlfAndJudgesLengthAfterNormalization() {
        assertThatCode(() -> validator.validate(MessageTemplateKind.PAYROLL, "Salarii",
                "Prima\r\nA doua\r\n{{signOff}}")).doesNotThrowAnyException();
    }

    /**
     * signOff is excluded from subjects because SignOff.names() returns "Sender\nFirm" — a multi-line
     * value that would split the Subject: header into continuation lines.
     */
    @Test
    void rejectsSignOffInSubject() {
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.PAYROLL,
                "Salarii {{signOff}}", "Bună ziua,\n\n{{signOff}}"))
                .hasMessageContaining("signOff");
    }

    /** A literal newline pasted into the subject field must be rejected. */
    @Test
    void rejectsNewlineInSubject() {
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.PAYROLL,
                "Salarii\nSub-linie", "Bună ziua"))
                .hasMessageContaining("newline");
    }

    /** CRLF in a subject normalises to LF before the newline check; the same rule applies. */
    @Test
    void rejectsCrlfInSubject() {
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.PAYROLL,
                "Salarii\r\nSub-linie", "Bună ziua"))
                .hasMessageContaining("newline");
    }
}
