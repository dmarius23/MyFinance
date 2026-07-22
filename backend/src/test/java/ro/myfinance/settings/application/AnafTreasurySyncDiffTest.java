package ro.myfinance.settings.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.settings.domain.PlatformTreasuryAccount;
import ro.myfinance.settings.domain.SyncChange;

/** The scraped-vs-live change classification (pure), independent of persistence. */
class AnafTreasurySyncDiffTest {

    private static final String CONT = "RO31TREZ0025503XXXXXXXXX";
    private static final String CAM = "RO02TREZ00220A470300XXXX";
    private static final String TVA_I = "RO77TREZ00220A100101XTVA";
    private static final String TVA_E = "RO24TREZ00220A100102XTVA";

    private static TreasuryIbans scraped(String c5503, String cam, String tvaIntern, String tvaExtern) {
        return new TreasuryIbans("Alba", "TREZ002", "Alba Iulia", "url", c5503, cam, tvaIntern, tvaExtern, null);
    }

    /** Live row where the 5503 IBAN fills impozite/CASS/CAS (cont unic), plus CAM + TVA intern/extern. */
    private static PlatformTreasuryAccount live(String c5503, String cam, String tvaIntern, String tvaExtern) {
        PlatformTreasuryAccount a = new PlatformTreasuryAccount("Alba Iulia", LocalDate.of(2020, 1, 1));
        a.setIbans(cam, c5503, c5503, c5503, tvaIntern, tvaExtern);
        return a;
    }

    @Test
    void addedWhenNoLiveRow() {
        assertThat(AnafTreasurySyncService.classify(scraped(CONT, CAM, TVA_I, TVA_E), null))
                .isEqualTo(SyncChange.ADDED);
    }

    @Test
    void unchangedWhenEveryIbanMatches() {
        assertThat(AnafTreasurySyncService.classify(scraped(CONT, CAM, TVA_I, TVA_E), live(CONT, CAM, TVA_I, TVA_E)))
                .isEqualTo(SyncChange.UNCHANGED);
    }

    @Test
    void changedWhenTheContUnicIbanDiffers() {
        assertThat(AnafTreasurySyncService.classify(scraped("RO00NEW", CAM, TVA_I, TVA_E), live(CONT, CAM, TVA_I, TVA_E)))
                .isEqualTo(SyncChange.CHANGED);
    }

    @Test
    void changedWhenExternTvaNewlyAppears() {
        // live has no extern yet; ANAF now publishes one -> CHANGED
        assertThat(AnafTreasurySyncService.classify(scraped(CONT, CAM, TVA_I, TVA_E), live(CONT, CAM, TVA_I, null)))
                .isEqualTo(SyncChange.CHANGED);
    }

    @Test
    void blankAndNullIbansAreTreatedAsEqual() {
        assertThat(AnafTreasurySyncService.classify(scraped(CONT, CAM, TVA_I, ""), live(CONT, CAM, TVA_I, null)))
                .isEqualTo(SyncChange.UNCHANGED);
    }
}
