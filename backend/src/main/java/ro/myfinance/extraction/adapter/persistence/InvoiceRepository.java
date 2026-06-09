package ro.myfinance.extraction.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.extraction.domain.Invoice;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    List<Invoice> findByCompanyIdAndPeriodMonth(UUID companyId, LocalDate periodMonth);
}
