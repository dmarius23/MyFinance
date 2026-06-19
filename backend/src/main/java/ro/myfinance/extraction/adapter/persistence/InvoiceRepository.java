package ro.myfinance.extraction.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.extraction.domain.Invoice;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    List<Invoice> findByCompanyIdAndPeriodMonth(UUID companyId, LocalDate periodMonth);

    // All invoices/receipts in a period across the tenant's companies (RLS-scoped). Used by the
    // Statements list to roll up each company's payment/matching status.
    List<Invoice> findByPeriodMonth(LocalDate periodMonth);

    List<Invoice> findByCompanyIdAndPeriodMonthBetween(UUID companyId, LocalDate from, LocalDate to);

    Optional<Invoice> findByDocumentId(UUID documentId);

    void deleteByDocumentId(UUID documentId);
}
