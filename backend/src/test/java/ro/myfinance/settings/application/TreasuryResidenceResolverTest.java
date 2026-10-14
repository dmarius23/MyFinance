package ro.myfinance.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ro.myfinance.settings.domain.PlatformTreasuryAccount;

/**
 * The treasury reference lists ~200 treasury-office towns. Most companies sit in a commune that has none,
 * so the residence has to be derived from ANAF's competent fiscal authority — these cases are taken from
 * real ANAF responses for the firm's own clients.
 */
class TreasuryResidenceResolverTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 8, 1);

    private TreasuryResidenceResolver resolverWith(String... residences) {
        PlatformTreasuryService treasury = mock(PlatformTreasuryService.class);
        // Real accounts, not mocks — the entity is what the resolver reads.
        when(treasury.listEffective(any())).thenReturn(
                List.of(residences).stream().map(r -> new PlatformTreasuryAccount(r, PERIOD)).toList());
        return new TreasuryResidenceResolver(treasury);
    }

    @Test
    void mapsTheCountyAdministrationToItsSeatTreasury() {
        // MERIC: ANAF says "Sat Floreşti Com. Floreşti" — a commune with NO treasury. The usable signal is
        // the county administration, whose treasury is the county seat, spelled "Cluj Napoca" here.
        var resolver = resolverWith("Cluj Napoca", "Turda", "Brasov", "Dej");

        assertThat(resolver.resolve("Administraţia Judeţeană a Finanţelor Publice Cluj",
                "Sat Floreşti Com. Floreşti", "CLUJ", PERIOD)).contains("Cluj Napoca");
    }

    @Test
    void matchesDirectlyWhenTheCountyNameIsAlsoTheTreasuryName() {
        // PRO ASSET: Sânpetru (Braşov). Confirmed by a real payment — it paid "Trezoreria Brasov".
        var resolver = resolverWith("Brasov", "Cluj Napoca", "Fagaras");

        assertThat(resolver.resolve("Administraţia Judeţeană a Finanţelor Publice Braşov",
                "Sat Sânpetru Com. Sânpetru", "BRAŞOV", PERIOD)).contains("Brasov");
    }

    @Test
    void prefersTheCompanysOwnTownWhenItHostsATreasury() {
        // A local fiscal office names the town itself — that treasury wins over the county seat.
        var resolver = resolverWith("Cluj Napoca", "Turda");

        assertThat(resolver.resolve("Serviciul Fiscal Municipal Turda", "Turda", "CLUJ", PERIOD))
                .contains("Turda");
    }

    @Test
    void fallsBackToTheLocalityThenTheCountyWhenTheAuthorityIsUnknown() {
        var resolver = resolverWith("Turda", "Cluj Napoca");

        assertThat(resolver.resolve(null, "Turda", "CLUJ", PERIOD)).contains("Turda");
        assertThat(resolver.resolve(null, "Sat Nicula Com. Fizesu Gherlii", "CLUJ", PERIOD))
                .contains("Cluj Napoca");
    }

    @Test
    void returnsEmptyRatherThanGuessingAWrongTreasury() {
        // Nothing matches the reference list ⇒ no residence. An IBAN for the WRONG treasury would send a
        // client's tax payment to the wrong account, so "unknown" is the only safe answer.
        var resolver = resolverWith("Cluj Napoca", "Brasov");

        assertThat(resolver.resolve("Administraţia Judeţeană a Finanţelor Publice Vaslui",
                "Sat Oltenesti", "VASLUI", PERIOD)).isEmpty();
        assertThat(resolver.resolve(null, null, null, PERIOD)).isEmpty();
    }

    @Test
    void extractsThePlaceFromAnAuthorityName() {
        assertThat(TreasuryResidenceResolver.placeOf(
                "Administraţia Judeţeană a Finanţelor Publice Cluj")).isEqualTo("Cluj");
        assertThat(TreasuryResidenceResolver.placeOf("Serviciul Fiscal Orăşenesc Huedin"))
                .isEqualTo("Huedin");
        assertThat(TreasuryResidenceResolver.placeOf(null)).isNull();
    }

    @Test
    void stripsSatComunaMarkersAndTheRepeatedName() {
        assertThat(TreasuryResidenceResolver.stripPlacePrefixes("Sat Floreşti Com. Floreşti"))
                .isEqualTo("Floreşti");
        assertThat(TreasuryResidenceResolver.stripPlacePrefixes("Municipiul Turda")).isEqualTo("Turda");
    }
}
