package ro.myfinance.settings.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.settings.domain.PlatformTreasuryAccount;

/** Global treasury-account reference data — no tenant scoping (these rows are national/public). */
public interface PlatformTreasuryAccountRepository extends JpaRepository<PlatformTreasuryAccount, UUID> {

    /** Effective-dated lookup: the account in force for {@code period} = greatest valid_from <= period. */
    Optional<PlatformTreasuryAccount> findTopByResidenceAndValidFromLessThanEqualOrderByValidFromDesc(
            String residence, LocalDate period);

    /** Full history for the admin screen: grouped by residence, newest effective date first. */
    List<PlatformTreasuryAccount> findAllByOrderByResidenceAscValidFromDesc();

    /** Guards the (residence, valid_from) uniqueness at the app layer for a friendly conflict message. */
    boolean existsByResidenceAndValidFrom(String residence, LocalDate validFrom);

    /** The exact effective-dated row (used by the ANAF sync to upsert idempotently). */
    Optional<PlatformTreasuryAccount> findByResidenceAndValidFrom(String residence, LocalDate validFrom);
}
