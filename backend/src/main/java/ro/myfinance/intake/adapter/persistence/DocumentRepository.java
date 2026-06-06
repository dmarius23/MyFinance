package ro.myfinance.intake.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.intake.domain.Document;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByCompanyIdOrderByUploadedAtDesc(UUID companyId);

    List<Document> findByCompanyIdAndPeriodMonthOrderByUploadedAtDesc(UUID companyId, LocalDate periodMonth);

    java.util.List<Document> findByPeriodMonth(java.time.LocalDate periodMonth);
}
