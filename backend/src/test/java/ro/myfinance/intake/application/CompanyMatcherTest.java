package ro.myfinance.intake.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A payslip prints only the company name (no CUI); a balance sheet prints both. Both must match. */
class CompanyMatcherTest {

    @Test
    void payslipMatchesByNameWithoutCui() {
        // A fluturaș never prints the fiscal code — only the company name.
        String text = "INNOVATECODE IT SRL\nFLUTURAS DE LICHIDARE APRILIE 2026\nDRAGOMIROIU MARIUS\nSalariu 4060";
        assertThat(CompanyMatcher.present(text, "49443957", "INNOVATECODE IT SRL")).isTrue();
    }

    @Test
    void balanceMatchesByCui() {
        String text = "INNOVATECODE IT SRL c.f. 49443957 r.c. J12/346/2024";
        assertThat(CompanyMatcher.present(text, "RO49443957", "Innovatecode IT")).isTrue();
    }

    @Test
    void otherCompanyDoesNotMatch() {
        String text = "OTHERCORP CONSULTING SRL c.f. 12345678 — stat de plată";
        assertThat(CompanyMatcher.present(text, "49443957", "INNOVATECODE IT SRL")).isFalse();
    }

    @Test
    void nameMatchIgnoresSpacingAndPunctuation() {
        assertThat(CompanyMatcher.present("Innovatecode  I.T.  S.R.L.", null, "INNOVATECODE IT SRL")).isTrue();
    }

    @Test
    void cannotVerifyWhenNoText() {
        assertThat(CompanyMatcher.present("", "49443957", "INNOVATECODE")).isNull();
        assertThat(CompanyMatcher.present(null, "49443957", "INNOVATECODE")).isNull();
    }

    @Test
    void amountDigitsDoNotFalseMatchCui() {
        // A different company's balanță with an amount that concatenates to the target CUI must NOT match
        // (the CUI is matched as a standalone number, not as a substring of all digits).
        String text = "ALTA FIRMA SRL\nTotal active 49 443 957.00 RON\nc.f. 11223344";
        assertThat(CompanyMatcher.present(text, "49443957", "INNOVATECODE IT SRL")).isFalse();
    }

    @Test
    void matchesRealCuiAsStandaloneNumber() {
        assertThat(CompanyMatcher.present("X SRL c.f. 49443957 r.c. ...", "RO49443957", "X")).isTrue();
    }

    @Test
    void commonNameWordDoesNotFalseMatchAccountingTerm() {
        // "Client Doi SRL" shares the word "Client" with account 4111 CLIENTI present in ANY balanță —
        // it must NOT match a different company's balance sheet.
        String innovBalance = "INNOVATECODE IT SRL c.f. 49443957\n4111  CLIENTI  15 346.77  0.00";
        assertThat(CompanyMatcher.present(innovBalance, "22334455", "Client Doi SRL")).isFalse();
    }

    @Test
    void clientDoiMatchesItsOwnDocument() {
        String own = "CLIENT DOI SRL c.f. 22334455\n4111 CLIENTI ...";
        assertThat(CompanyMatcher.present(own, "22334455", "Client Doi SRL")).isTrue();
        // even without the CUI (e.g. a payslip), the full name still matches
        assertThat(CompanyMatcher.present("CLIENT DOI SRL\nFLUTURAS ...", null, "Client Doi SRL")).isTrue();
    }
}
