package ro.myfinance.intake;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ro.myfinance.intake.domain.DocumentType;
import ro.myfinance.intake.domain.DriveDocLayout;

/**
 * The type ↔ folder/filename mapping for the accounting-firm Drive layout: declaration form-code folders
 * ({@code D700}, {@code D 112}, {@code D100 Chirie}), descriptive balance folders, and the filename hints
 * used for files dropped straight in a month folder ({@code fluturas#…}, {@code D700_<CUI>_<YYYY>_<MM>…}).
 */
class DriveDocLayoutTest {

    @Test
    void recognisesDeclarationFormCodeFolders() {
        assertThat(DriveDocLayout.typeOf("D700")).contains(DocumentType.DECLARATION);
        assertThat(DriveDocLayout.typeOf("D 112")).contains(DocumentType.DECLARATION);   // note the space
        assertThat(DriveDocLayout.typeOf("D300")).contains(DocumentType.DECLARATION);
        assertThat(DriveDocLayout.typeOf("D394")).contains(DocumentType.DECLARATION);
        assertThat(DriveDocLayout.typeOf("D100 Chirie")).contains(DocumentType.DECLARATION);
        assertThat(DriveDocLayout.typeOf("D 100 dividende")).contains(DocumentType.DECLARATION);
        assertThat(DriveDocLayout.typeOf("D060")).contains(DocumentType.DECLARATION);
        assertThat(DriveDocLayout.typeOf("D406")).contains(DocumentType.DECLARATION);
    }

    @Test
    void recognisesBalanceAndPayrollFolders() {
        assertThat(DriveDocLayout.typeOf("balanta de verificare 2026")).contains(DocumentType.TRIAL_BALANCE);
        assertThat(DriveDocLayout.typeOf("State de plata")).contains(DocumentType.PAYROLL);
    }

    @Test
    void doesNotTypeGenericOrPeriodFolders() {
        assertThat(DriveDocLayout.typeOf("7. Iulie 2026")).isEmpty();
        assertThat(DriveDocLayout.typeOf("Bilant interimar T2 an 2026")).isEmpty();
        assertThat(DriveDocLayout.typeOf("STONEAGE INDUSTRY SRL")).isEmpty();
        assertThat(DriveDocLayout.typeOf("SAF-T")).isEmpty();
    }

    @Test
    void typesLooseFilesFromTheirName() {
        assertThat(DriveDocLayout.typeOfFileName("fluturas#_STONEAGE INDUSTRY SRL_2026_07.pdf"))
                .contains(DocumentType.PAYROLL);
        assertThat(DriveDocLayout.typeOfFileName("Pontaj#_MAX CONSTRUCT HOME SRL_2026_07.pdf"))
                .contains(DocumentType.PAYROLL);
        assertThat(DriveDocLayout.typeOfFileName("Stat_salarii#_AB INVEST PROD SRL_2026_07.pdf"))
                .contains(DocumentType.PAYROLL);
        assertThat(DriveDocLayout.typeOfFileName("D700_44570402_2026_07 - trecere impozit profit.pdf"))
                .contains(DocumentType.DECLARATION);
        assertThat(DriveDocLayout.typeOfFileName("d112_35013518_2026_07.pdf"))
                .contains(DocumentType.DECLARATION);
        // Trial balance as a plain file; the full statement ("Bilant …") must NOT be typed as a balance.
        assertThat(DriveDocLayout.typeOfFileName("balanta_de_verificare martie 2026.pdf"))
                .contains(DocumentType.TRIAL_BALANCE);
        assertThat(DriveDocLayout.typeOfFileName("Bilant interimar iunie 2026.pdf")).isEmpty();
        assertThat(DriveDocLayout.typeOfFileName("random-document.pdf")).isEmpty();
    }
}
