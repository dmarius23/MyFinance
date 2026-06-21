package ro.myfinance.payroll.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.payroll.domain.PayrollEmail;

public interface PayrollEmailRepository extends JpaRepository<PayrollEmail, UUID> {

    List<PayrollEmail> findByCompanyIdAndPeriodMonthOrderBySentAtDesc(UUID companyId, LocalDate periodMonth);

    List<PayrollEmail> findByPeriodMonthOrderBySentAtDesc(LocalDate periodMonth);
}
