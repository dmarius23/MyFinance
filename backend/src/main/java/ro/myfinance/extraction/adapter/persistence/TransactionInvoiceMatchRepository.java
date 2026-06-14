package ro.myfinance.extraction.adapter.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ro.myfinance.extraction.domain.TransactionInvoiceMatch;

public interface TransactionInvoiceMatchRepository extends JpaRepository<TransactionInvoiceMatch, UUID> {

    List<TransactionInvoiceMatch> findByTransactionIdIn(List<UUID> transactionIds);

    List<TransactionInvoiceMatch> findByInvoiceIdIn(List<UUID> invoiceIds);

    boolean existsByTransactionIdAndInvoiceId(UUID transactionId, UUID invoiceId);

    void deleteByTransactionIdAndInvoiceId(UUID transactionId, UUID invoiceId);

    @Query("select coalesce(sum(m.allocatedAmount), 0) from TransactionInvoiceMatch m where m.invoiceId = :invoiceId")
    BigDecimal sumAllocatedByInvoice(@Param("invoiceId") UUID invoiceId);

    @Query("select coalesce(sum(m.allocatedAmount), 0) from TransactionInvoiceMatch m where m.transactionId = :transactionId")
    BigDecimal sumAllocatedByTransaction(@Param("transactionId") UUID transactionId);
}
