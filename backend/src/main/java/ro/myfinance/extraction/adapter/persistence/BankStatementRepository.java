package ro.myfinance.extraction.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.extraction.domain.BankStatement;

public interface BankStatementRepository extends JpaRepository<BankStatement, UUID> {

    List<BankStatement> findByCompanyIdAndPeriodMonth(UUID companyId, LocalDate periodMonth);

    java.util.List<ro.myfinance.extraction.domain.BankStatement> findByPeriodMonth(java.time.LocalDate periodMonth);

    void deleteByDocumentId(UUID documentId);
}
