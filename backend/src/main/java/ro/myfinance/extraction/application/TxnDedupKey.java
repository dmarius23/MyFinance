package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import ro.myfinance.extraction.domain.BankTransaction;

/**
 * The identity of a bank transaction for de-duplication: the same real transaction appearing in two
 * overlapping statement files (e.g. a Jan–Feb export and a January-only export) collapses to one key.
 * Keyed on the running balance when the bank prints one (BRD) — the strongest signal — else on the
 * date + amount + normalized description + reference. Shared by extraction (within-file) and the
 * reconcile view/engine (store-then-dedupe-at-view, across files).
 */
final class TxnDedupKey {

    private TxnDedupKey() {
    }

    static String of(BankTransaction t) {
        return of(t.getAccountIban(), t.getTxnDate(), t.getAmount(), t.getBalanceAfter(),
                t.getDescription(), t.getRef());
    }

    static String of(String accountIban, LocalDate date, BigDecimal amount, BigDecimal balanceAfter,
                     String description, String ref) {
        if (balanceAfter != null) {
            return "B|" + accountIban + "|" + date + "|" + amount.stripTrailingZeros().toPlainString()
                    + "|" + balanceAfter.stripTrailingZeros().toPlainString();
        }
        return "F|" + accountIban + "|" + date + "|" + amount.stripTrailingZeros().toPlainString() + "|"
                + ReconText.normalize(description) + "|" + (ref == null ? "" : ref);
    }
}
