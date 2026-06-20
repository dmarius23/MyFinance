package ro.myfinance.taxpayments.domain;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * A parsed ANAF declaration. The itemized {@link #obligations()} are authoritative; {@link #declaredTotal()}
 * is the form's header total (totalPlata_A) kept only as a cross-check — it is null when the form has no
 * reliable header total (e.g. D300). {@link #totalsMismatch()} flags when the header disagrees with the sum.
 */
public record ParsedDeclaration(DeclarationType type, String cui, String name, YearMonth period,
                                List<TaxObligation> obligations, BigDecimal declaredTotal) {

    /** Total amount to pay = sum of payable obligations (refunds are excluded). */
    public BigDecimal computedTotal() {
        return obligations.stream().filter(TaxObligation::payable)
                .map(TaxObligation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean totalsMismatch() {
        return declaredTotal != null && declaredTotal.compareTo(computedTotal()) != 0;
    }
}
