package ro.myfinance.extraction.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.extraction.domain.TransactionRule;

public interface TransactionRuleRepository extends JpaRepository<TransactionRule, UUID> {

    List<TransactionRule> findByCompanyId(UUID companyId);
}
