package ro.myfinance.settings.adapter.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.settings.domain.CountyTreasuryAccount;

public interface CountyTreasuryAccountRepository extends JpaRepository<CountyTreasuryAccount, UUID> {

    // RLS scopes this to the current tenant automatically.
    boolean existsByCountyAndTaxType(String county, String taxType);
}
