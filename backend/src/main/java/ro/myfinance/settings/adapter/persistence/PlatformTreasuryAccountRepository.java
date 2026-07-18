package ro.myfinance.settings.adapter.persistence;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.settings.domain.PlatformTreasuryAccount;

/** Global treasury-account reference data — no tenant scoping (these rows are national/public). */
public interface PlatformTreasuryAccountRepository extends JpaRepository<PlatformTreasuryAccount, UUID> {

    /** Effective-dated lookup: the account in force for {@code period} = greatest valid_from <= period. */
    Optional<PlatformTreasuryAccount> findTopByResidenceAndValidFromLessThanEqualOrderByValidFromDesc(
            String residence, LocalDate period);
}
