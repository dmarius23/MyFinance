package ro.myfinance.reports.adapter.persistence;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.reports.application.Granularity;
import ro.myfinance.reports.domain.PeriodReport;

public interface PeriodReportRepository extends JpaRepository<PeriodReport, UUID> {

    Optional<PeriodReport> findByCompanyIdAndGranularityAndPeriodStart(
            UUID companyId, Granularity granularity, LocalDate periodStart);
}
