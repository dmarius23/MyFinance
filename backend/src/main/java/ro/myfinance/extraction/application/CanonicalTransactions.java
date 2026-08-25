package ro.myfinance.extraction.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.extraction.adapter.persistence.BankTransactionRepository;
import ro.myfinance.extraction.domain.BankTransaction;

/**
 * The store-then-dedupe-at-view accessor: reconciliation reads transactions by their own date and collapses
 * duplicates that arise when overlapping files (e.g. a Jan–Feb export and a January export) each store their
 * own rows. One canonical row survives per {@link TxnDedupKey}, so a month never double-counts a transaction.
 * All reconciliation reads + the auto-matcher route through here.
 */
@Component
class CanonicalTransactions {

    private final BankTransactionRepository transactions;

    CanonicalTransactions(BankTransactionRepository transactions) {
        this.transactions = transactions;
    }

    /** Deduped transactions dated in {@code month}, newest first. */
    @Transactional(readOnly = true)
    List<BankTransaction> forMonth(UUID companyId, LocalDate month) {
        LocalDate m = month.withDayOfMonth(1);
        return dedupe(transactions.findByCompanyIdAndTxnDateGreaterThanEqualAndTxnDateLessThan(
                companyId, m, m.plusMonths(1)));
    }

    /** Deduped transactions dated in [{@code from}, {@code toExclusive}), newest first (rolling windows). */
    @Transactional(readOnly = true)
    List<BankTransaction> between(UUID companyId, LocalDate from, LocalDate toExclusive) {
        return dedupe(transactions.findByCompanyIdAndTxnDateGreaterThanEqualAndTxnDateLessThan(
                companyId, from, toExclusive));
    }

    /** Deduped month transactions per company across the whole tenant (completeness roll-up). */
    @Transactional(readOnly = true)
    Map<UUID, List<BankTransaction>> byCompanyForMonth(LocalDate month) {
        LocalDate m = month.withDayOfMonth(1);
        Map<UUID, List<BankTransaction>> byCompany = new HashMap<>();
        for (BankTransaction t : transactions.findByTxnDateGreaterThanEqualAndTxnDateLessThan(m, m.plusMonths(1))) {
            byCompany.computeIfAbsent(t.getCompanyId(), k -> new ArrayList<>()).add(t);
        }
        byCompany.replaceAll((k, v) -> dedupe(v));
        return byCompany;
    }

    /** Collapse to one canonical row per key (the lowest id — stable across re-runs), newest first. */
    static List<BankTransaction> dedupe(List<BankTransaction> txns) {
        Map<String, BankTransaction> canonical = new HashMap<>();
        for (BankTransaction t : txns) {
            canonical.merge(TxnDedupKey.of(t), t, (a, b) -> a.getId().compareTo(b.getId()) <= 0 ? a : b);
        }
        return canonical.values().stream()
                .sorted(Comparator.comparing(BankTransaction::getTxnDate).reversed()
                        .thenComparing(BankTransaction::getId))
                .toList();
    }
}
