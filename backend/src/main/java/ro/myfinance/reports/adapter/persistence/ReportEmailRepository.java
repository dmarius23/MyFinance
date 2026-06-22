package ro.myfinance.reports.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.reports.domain.ReportEmail;

public interface ReportEmailRepository extends JpaRepository<ReportEmail, UUID> {

    List<ReportEmail> findByCompanyIdAndPeriodMonthOrderBySentAtDesc(UUID companyId, LocalDate periodMonth);

    List<ReportEmail> findByPeriodMonthOrderBySentAtDesc(LocalDate periodMonth);
}
