package ro.myfinance.access.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The invitation email is branded to MyFinance + the firm, embeds the link, and degrades gracefully. */
class InviteMessageFactoryTest {

    @Test
    void subjectCarriesTheFirmName() {
        assertThat(InviteMessageFactory.subject("Contabil Expert SRL"))
                .isEqualTo("Invitație în portalul MyFinance — Contabil Expert SRL");
    }

    @Test
    void subjectOmitsSuffixWhenFirmMissing() {
        assertThat(InviteMessageFactory.subject(null)).isEqualTo("Invitație în portalul MyFinance");
        assertThat(InviteMessageFactory.subject("  ")).isEqualTo("Invitație în portalul MyFinance");
    }

    @Test
    void bodyIncludesFirmCompanyRepAndLink() {
        String body = InviteMessageFactory.body("Contabil Expert SRL", "ACME SRL", "Ana Pop",
                "https://app.example/set#token");
        assertThat(body)
                .contains("Ana Pop")           // greeting
                .contains("Contabil Expert SRL") // firm (twice: intro + signature)
                .contains("ACME SRL")           // company
                .contains("https://app.example/set#token") // the link
                .contains("MyFinance");
    }

    @Test
    void bodyDegradesWhenOptionalFieldsMissing() {
        String body = InviteMessageFactory.body(null, null, null, "https://app.example/set#token");
        assertThat(body)
                .startsWith("Bună ziua,")                 // no name → neutral greeting
                .contains("firma de contabilitate")        // no firm → neutral phrase
                .doesNotContain(" pentru .")               // no company clause
                .contains("https://app.example/set#token");
    }
}
