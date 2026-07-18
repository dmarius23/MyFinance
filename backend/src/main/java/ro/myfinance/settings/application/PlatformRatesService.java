package ro.myfinance.settings.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.settings.adapter.persistence.PlatformTaxRateRepository;
import ro.myfinance.settings.domain.PlatformTaxRate;
import ro.myfinance.settings.domain.TaxRateCategory;

/**
 * Period-aware resolver for the global national tax rates. GLOBAL, not tenant-scoped — the tables
 * hold public reference data (see V35). A period's rate is the one whose {@code valid_from} is the
 * greatest value still {@code <=} the period.
 */
@Service
@Transactional(readOnly = true)
public class PlatformRatesService {

    private final PlatformTaxRateRepository rates;

    public PlatformRatesService(PlatformTaxRateRepository rates) {
        this.rates = rates;
    }

    /** The rate in force for {@code period}, or empty if none is configured at/before it. */
    public Optional<BigDecimal> rateFor(TaxRateCategory category, LocalDate period) {
        if (category == null || period == null) {
            return Optional.empty();
        }
        return rates.findTopByCategoryAndValidFromLessThanEqualOrderByValidFromDesc(category, period)
                .map(PlatformTaxRate::getRate);
    }
}
