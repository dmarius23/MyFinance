package ro.myfinance.company.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.company.domain.TreasuryAccount;

public interface TreasuryAccountRepository extends JpaRepository<TreasuryAccount, UUID> {

    List<TreasuryAccount> findByCompanyId(UUID companyId);
}
