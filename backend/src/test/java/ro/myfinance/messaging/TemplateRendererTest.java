package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.messaging.TemplateRenderer;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void substitutesKnownPlaceholders() {
        String out = renderer.render("Bună ziua, {{companyName}} ({{cui}})",
                Map.of("companyName", "Acme SRL", "cui", "RO123"));
        assertThat(out).isEqualTo("Bună ziua, Acme SRL (RO123)");
    }

    @Test
    void toleratesWhitespaceInsideDelimiters() {
        String out = renderer.render("luna {{ month }}", Map.of("month", "Aprilie 2026"));
        assertThat(out).isEqualTo("luna Aprilie 2026");
    }

    @Test
    void insertsMultiLineBlocksVerbatim() {
        String out = renderer.render("Conturi:\n{{paymentAccounts}}\nGata",
                Map.of("paymentAccounts", "- 100 lei\n- Beneficiar: Trezoreria Cluj"));
        assertThat(out).isEqualTo("Conturi:\n- 100 lei\n- Beneficiar: Trezoreria Cluj\nGata");
    }

    @Test
    void blankValueRendersEmptyWithinALineThatHasOtherText() {
        String out = renderer.render("Total: {{totalToPay}} lei", Map.of("totalToPay", ""));
        assertThat(out).isEqualTo("Total:  lei");
    }

    /** A stale name from an older contract must never reach a client literally. */
    @Test
    void unknownPlaceholderIsStrippedNotEchoed() {
        String out = renderer.render("Salut {{removedInV7}}!", Map.of());
        assertThat(out).isEqualTo("Salut !");
        assertThat(out).doesNotContain("{{");
    }

    @Test
    void dropsALineWhoseOnlyContentIsAnEmptyPlaceholder() {
        String out = renderer.render("O zi bună,\n{{signOff}}", Map.of("signOff", ""));
        assertThat(out).isEqualTo("O zi bună,");
    }

    @Test
    void keepsALineWhosePlaceholderHasAValue() {
        String out = renderer.render("O zi bună,\n{{signOff}}",
                Map.of("signOff", "Maria Popescu\nContaZone SRL"));
        assertThat(out).isEqualTo("O zi bună,\nMaria Popescu\nContaZone SRL");
    }

    @Test
    void keepsDeliberateBlankLinesThatContainNoPlaceholder() {
        String out = renderer.render("Prima\n\nA doua", Map.of());
        assertThat(out).isEqualTo("Prima\n\nA doua");
    }

    @Test
    void nullTemplateRendersEmpty() {
        assertThat(renderer.render(null, Map.of())).isEmpty();
    }

    /**
     * A company or partner name may itself contain {{…}}. Substitution is single-pass,
     * so injected data is never re-scanned — otherwise client data would reach the template engine.
     */
    @Test
    void valuesContainingPlaceholderSyntaxAreInsertedLiterally() {
        String out = renderer.render("Firma {{companyName}} datorează {{total}}",
                Map.of("companyName", "Acme {{total}} SRL", "total", "500"));
        assertThat(out).isEqualTo("Firma Acme {{total}} SRL datorează 500");
    }

    @Test
    void unbalancedDelimitersInATemplateAreLeftAlone() {
        String out = renderer.render("Suma {{ este mare", Map.of("month", "Aprilie 2026"));
        assertThat(out).isEqualTo("Suma {{ este mare");
    }

    /** A template pasted from Word must not put \r into a client's email. */
    @Test
    void normalizesWindowsAndClassicMacLineEndings() {
        assertThat(renderer.render("Prima\r\nA doua\rA treia", Map.of()))
                .isEqualTo("Prima\nA doua\nA treia")
                .doesNotContain("\r");
    }

    /**
     * A tenant may delete a block placeholder. That is their prose decision — rendering
     * must not throw and must not reinstate the list; the message simply goes out without it.
     */
    @Test
    void templateOmittingItsBlockStillRenders() {
        String out = renderer.render("Bună ziua,\n\nTrimiteți documentele pentru {{month}}.",
                Map.of("month", "Aprilie 2026", "missingTransactions", "• 15.04.2026 — Acme — 100 RON"));
        assertThat(out).isEqualTo("Bună ziua,\n\nTrimiteți documentele pentru Aprilie 2026.");
        assertThat(out).doesNotContain("15.04.2026");
    }

    /**
     * Elision is gated on EVERY placeholder being empty. One placeholder with a value keeps the line,
     * even when its literal text is blank and its other placeholder renders empty.
     */
    @Test
    void keepsALineWhenOnlyOneOfTwoPlaceholdersIsEmpty() {
        String out = renderer.render("{{senderName}} {{firmName}}",
                Map.of("senderName", "Maria Popescu", "firmName", ""));
        assertThat(out).isEqualTo("Maria Popescu ");
    }
}
