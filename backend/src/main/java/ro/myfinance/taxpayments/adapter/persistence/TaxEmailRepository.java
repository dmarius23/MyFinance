package ro.myfinance.taxpayments.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.taxpayments.domain.TaxEmail;

public interface TaxEmailRepository extends JpaRepository<TaxEmail, UUID> {

    List<TaxEmail> findByCompanyIdAndPeriodMonthOrderBySentAtDesc(UUID companyId, LocalDate periodMonth);
}
