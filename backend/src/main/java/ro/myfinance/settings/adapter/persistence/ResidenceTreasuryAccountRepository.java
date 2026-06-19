package ro.myfinance.settings.adapter.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.settings.domain.ResidenceTreasuryAccount;

public interface ResidenceTreasuryAccountRepository extends JpaRepository<ResidenceTreasuryAccount, UUID> {

    // RLS scopes this to the current tenant automatically.
    boolean existsByResidenceAndIban(String residence, String iban);
}
