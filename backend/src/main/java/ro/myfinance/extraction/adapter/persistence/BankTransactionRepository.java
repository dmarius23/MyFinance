package ro.myfinance.extraction.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.extraction.domain.BankTransaction;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {

    List<BankTransaction> findByStatementIdInOrderByTxnDateDesc(List<UUID> statementIds);
}
