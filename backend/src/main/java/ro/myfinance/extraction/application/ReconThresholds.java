package ro.myfinance.extraction.application;

import java.math.BigDecimal;

/**
 * Shared money/date tolerances for reconciliation. Kept in one place so the classifier, the matcher and
 * the read views agree on what "the same amount" and "close enough date" mean.
 */
final class ReconThresholds {

    private ReconThresholds() {
    }

    /** Amounts within 0.01 are treated as equal — absorbs rounding in extracted totals. */
    static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    /**
     * How many days a payment may precede the extracted invoice date and still auto-match. The extracted
     * date can land on a due date while the payment was made a few days earlier; with an exact IBAN +
     * amount match already in hand, a small backward window avoids rejecting it.
     */
    static final long DATE_BACK_TOLERANCE_DAYS = 10;
}
