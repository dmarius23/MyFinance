package ro.myfinance.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ro.myfinance.settings.adapter.persistence.PlatformTreasuryAccountRepository;
import ro.myfinance.settings.domain.PlatformTreasuryAccount;

/**
 * A company's locality ("Cluj-Napoca", "București") must resolve against the ANAF catalogue's spelling
 * ("Cluj Napoca", "Bucuresti") — matching is diacritic- and separator-insensitive, but only an exact
 * normalized match counts (a wrong treasury IBAN would misdirect a tax payment).
 */
class PlatformTreasuryServiceTest {

    private final PlatformTreasuryAccountRepository repo = mock(PlatformTreasuryAccountRepository.class);
    private final PlatformTreasuryService service = new PlatformTreasuryService(repo);
    private final LocalDate period = LocalDate.of(2026, 9, 1);

    private static PlatformTreasuryAccount acct(String residence) {
        return new PlatformTreasuryAccount(residence, LocalDate.of(2026, 1, 14));
    }

    @Test
    void normalizeStripsDiacriticsAndSeparators() {
        assertThat(PlatformTreasuryService.normalizeResidence("Cluj-Napoca")).isEqualTo("cluj napoca");
        assertThat(PlatformTreasuryService.normalizeResidence("București")).isEqualTo("bucuresti");
        assertThat(PlatformTreasuryService.normalizeResidence("  BUCUREŞTI ")).isEqualTo("bucuresti");
    }

    @Test
    void resolvesDespiteHyphenAndDiacritics() {
        when(repo.findTopByResidenceAndValidFromLessThanEqualOrderByValidFromDesc(any(), any()))
                .thenReturn(Optional.empty()); // no exact spelling in the catalogue
        PlatformTreasuryAccount cluj = acct("Cluj Napoca");
        PlatformTreasuryAccount buc = acct("Bucuresti");
        when(repo.findAllByOrderByResidenceAscValidFromDesc()).thenReturn(List.of(buc, cluj));

        assertThat(service.accountFor("Cluj-Napoca", period)).contains(cluj);
        assertThat(service.accountFor("București", period)).contains(buc);
        // No candidate → still missing (we never guess a treasury IBAN).
        assertThat(service.accountFor("Timișoara", period)).isEmpty();
    }

    @Test
    void prefersExactMatchFastPath() {
        PlatformTreasuryAccount exact = acct("Cluj Napoca");
        when(repo.findTopByResidenceAndValidFromLessThanEqualOrderByValidFromDesc("Cluj Napoca", period))
                .thenReturn(Optional.of(exact));

        assertThat(service.accountFor("Cluj Napoca", period)).contains(exact);
    }
}
