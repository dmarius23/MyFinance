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
}
