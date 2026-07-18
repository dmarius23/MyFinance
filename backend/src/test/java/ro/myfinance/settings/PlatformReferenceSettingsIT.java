package ro.myfinance.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ro.myfinance.settings.adapter.persistence.PlatformTaxRateRepository;
import ro.myfinance.settings.adapter.persistence.PlatformTreasuryAccountRepository;
import ro.myfinance.settings.application.PlatformRatesService;
import ro.myfinance.settings.application.PlatformTreasuryService;
import ro.myfinance.settings.domain.PlatformTaxRate;
import ro.myfinance.settings.domain.PlatformTreasuryAccount;
import ro.myfinance.settings.domain.TaxRateCategory;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Global reference settings (Phase 1): the tables carry no tenant_id / no RLS, so there is no
 * cross-tenant isolation test here (intentional — see V35). What matters is the effective-dated
 * resolution: a period picks the row with the greatest valid_from that is still <= the period.
 * Rows created here are cleaned up so they don't leak into other ITs sharing the container.
 */
class PlatformReferenceSettingsIT extends AbstractPostgresIT {

    @Autowired PlatformRatesService rates;
    @Autowired PlatformTreasuryService treasury;
    @Autowired PlatformTaxRateRepository rateRepo;
    @Autowired PlatformTreasuryAccountRepository treasuryRepo;

    private final List<UUID> createdRates = new ArrayList<>();
    private final List<UUID> createdAccounts = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdRates.forEach(rateRepo::deleteById);
        createdAccounts.forEach(treasuryRepo::deleteById);
        createdRates.clear();
        createdAccounts.clear();
    }

    @Test
    void v35SeedsTheCurrentNationalRates() {
        LocalDate now = LocalDate.of(2026, 7, 1);
        assertThat(rates.rateFor(TaxRateCategory.VAT, now)).contains(new BigDecimal("21.00"));
        assertThat(rates.rateFor(TaxRateCategory.MICRO, now)).contains(new BigDecimal("3.00"));
        assertThat(rates.rateFor(TaxRateCategory.PROFIT, now)).contains(new BigDecimal("16.00"));
    }

    @Test
    void rateResolvesToTheValueInForceForThePeriod() {
        // A later transition on top of the 2020 seed (VAT 21 -> 25 from 2026-08-01).
        PlatformTaxRate later = rateRepo.save(
                new PlatformTaxRate(TaxRateCategory.VAT, new BigDecimal("25.00"), LocalDate.of(2026, 8, 1)));
        createdRates.add(later.getId());

        // Before the transition: the 2020 seed (21) still wins.
        assertThat(rates.rateFor(TaxRateCategory.VAT, LocalDate.of(2026, 7, 31)))
                .contains(new BigDecimal("21.00"));
        // On/after the transition: the newer row (25) wins.
        assertThat(rates.rateFor(TaxRateCategory.VAT, LocalDate.of(2026, 8, 1)))
                .contains(new BigDecimal("25.00"));
        // Before any configured row: nothing resolves.
        assertThat(rates.rateFor(TaxRateCategory.VAT, LocalDate.of(2019, 12, 31))).isEmpty();
    }

    @Test
    void treasuryResolvesToTheAccountInForceForThePeriod() {
        String residence = "Testburg-" + UUID.randomUUID();

        PlatformTreasuryAccount oldAcc = new PlatformTreasuryAccount(residence, LocalDate.of(2020, 1, 1));
        oldAcc.setIbans(null, null, null, null, "RO00OLD0000000000000001");
        createdAccounts.add(treasuryRepo.save(oldAcc).getId());

        PlatformTreasuryAccount newAcc = new PlatformTreasuryAccount(residence, LocalDate.of(2026, 1, 1));
        newAcc.setIbans(null, null, null, null, "RO00NEW0000000000000001");
        createdAccounts.add(treasuryRepo.save(newAcc).getId());

        assertThat(treasury.accountFor(residence, LocalDate.of(2025, 6, 1)))
                .get().extracting(PlatformTreasuryAccount::getIbanTva).isEqualTo("RO00OLD0000000000000001");
        assertThat(treasury.accountFor(residence, LocalDate.of(2026, 6, 1)))
                .get().extracting(PlatformTreasuryAccount::getIbanTva).isEqualTo("RO00NEW0000000000000001");
        assertThat(treasury.accountFor(residence, LocalDate.of(2019, 1, 1))).isEmpty();
        assertThat(treasury.accountFor("no-such-residence", LocalDate.of(2026, 6, 1))).isEmpty();
    }
}
