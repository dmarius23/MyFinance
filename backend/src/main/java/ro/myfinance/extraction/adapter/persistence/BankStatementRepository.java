package ro.myfinance.extraction.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.extraction.domain.BankStatement;

public interface BankStatementRepository extends JpaRepository<BankStatement, UUID> {

    List<BankStatement> findByCompanyIdAndPeriodMonth(UUID companyId, LocalDate periodMonth);

    List<BankStatement> findByCompanyIdAndPeriodMonthBetween(UUID companyId, LocalDate from, LocalDate to);

    /** Statement files whose covered range overlaps the month [monthStart, monthEnd] — so a multi-month
     *  file is listed under every month it touches. */
    List<BankStatement> findByCompanyIdAndFirstTxnDateLessThanEqualAndLastTxnDateGreaterThanEqual(
            UUID companyId, LocalDate monthEnd, LocalDate monthStart);

    java.util.List<ro.myfinance.extraction.domain.BankStatement> findByPeriodMonth(java.time.LocalDate periodMonth);

    boolean existsByDocumentId(UUID documentId);

    java.util.Optional<BankStatement> findByDocumentId(UUID documentId);

    void deleteByDocumentId(UUID documentId);
}
