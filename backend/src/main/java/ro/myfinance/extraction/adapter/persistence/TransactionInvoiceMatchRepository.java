package ro.myfinance.extraction.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.extraction.domain.TransactionInvoiceMatch;

public interface TransactionInvoiceMatchRepository extends JpaRepository<TransactionInvoiceMatch, UUID> {

    List<TransactionInvoiceMatch> findByTransactionIdIn(List<UUID> transactionIds);

    List<TransactionInvoiceMatch> findByInvoiceIdIn(List<UUID> invoiceIds);

    boolean existsByTransactionIdAndInvoiceId(UUID transactionId, UUID invoiceId);

    void deleteByTransactionIdAndInvoiceId(UUID transactionId, UUID invoiceId);
}
