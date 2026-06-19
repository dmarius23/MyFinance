package ro.myfinance.settings.adapter.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.settings.domain.ResidenceTreasuryAccount;

public interface ResidenceTreasuryAccountRepository extends JpaRepository<ResidenceTreasuryAccount, UUID> {

    // RLS scopes these to the current tenant automatically. One row per residence (unique).
    boolean existsByResidence(String residence);

    Optional<ResidenceTreasuryAccount> findByResidence(String residence);
}
