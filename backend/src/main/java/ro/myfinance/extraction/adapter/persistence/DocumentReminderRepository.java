package ro.myfinance.extraction.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.extraction.domain.DocumentReminder;

public interface DocumentReminderRepository extends JpaRepository<DocumentReminder, UUID> {

    List<DocumentReminder> findByCompanyIdAndPeriodMonthOrderBySentAtDesc(UUID companyId, LocalDate periodMonth);

    List<DocumentReminder> findByPeriodMonthOrderBySentAtDesc(LocalDate periodMonth);
}
