package ro.myfinance.settings.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
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

    /** Full history for the admin screen: grouped by category, newest effective date first. */
    List<PlatformTaxRate> findAllByOrderByCategoryAscValidFromDesc();

    /** Guards the (category, valid_from) uniqueness at the app layer for a friendly conflict message. */
    boolean existsByCategoryAndValidFrom(TaxRateCategory category, LocalDate validFrom);
}
