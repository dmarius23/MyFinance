package ro.myfinance.extraction.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.extraction.domain.BankTransaction;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {

    List<BankTransaction> findByStatementIdInOrderByTxnDateDesc(List<UUID> statementIds);

    java.util.List<ro.myfinance.extraction.domain.BankTransaction> findByCompanyId(java.util.UUID companyId);

    /** A company's transactions whose date is in [from, toExclusive) — the reconcile month/window read,
     *  before de-duplication across overlapping files. */
    List<BankTransaction> findByCompanyIdAndTxnDateGreaterThanEqualAndTxnDateLessThan(
            UUID companyId, java.time.LocalDate from, java.time.LocalDate toExclusive);

    /** Every tenant transaction dated in [from, toExclusive) — cross-company monthly roll-up (RLS-scoped). */
    List<BankTransaction> findByTxnDateGreaterThanEqualAndTxnDateLessThan(
            java.time.LocalDate from, java.time.LocalDate toExclusive);
}
