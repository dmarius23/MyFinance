package ro.myfinance.reports.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.reports.domain.ReportSnapshot;

public interface ReportSnapshotRepository extends JpaRepository<ReportSnapshot, UUID> {

    Optional<ReportSnapshot> findByCompanyIdAndPeriodMonth(UUID companyId, LocalDate periodMonth);

    List<ReportSnapshot> findByPeriodMonth(LocalDate periodMonth);

    List<ReportSnapshot> findByCompanyIdAndPeriodMonthLessThanEqualOrderByPeriodMonthAsc(
            UUID companyId, LocalDate periodMonth);

    /** All monthly snapshots for a company within a calendar period, chronological. */
    List<ReportSnapshot> findByCompanyIdAndPeriodMonthBetweenOrderByPeriodMonthAsc(
            UUID companyId, LocalDate from, LocalDate to);

    void deleteByDocumentId(UUID documentId);
}
