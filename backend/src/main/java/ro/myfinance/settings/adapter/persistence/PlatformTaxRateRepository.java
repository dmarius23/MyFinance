package ro.myfinance.settings.adapter.persistence;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.settings.domain.PlatformTaxRate;
import ro.myfinance.settings.domain.TaxRateCategory;

/** Global tax-rate reference data — no tenant scoping (these rows are national/public). */
public interface PlatformTaxRateRepository extends JpaRepository<PlatformTaxRate, UUID> {

    /** Effective-dated lookup: the rate in force for {@code period} = greatest valid_from <= period. */
    Optional<PlatformTaxRate> findTopByCategoryAndValidFromLessThanEqualOrderByValidFromDesc(
            TaxRateCategory category, LocalDate period);
}
