package ro.myfinance.taxpayments.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import ro.myfinance.common.i18n.RomanianMonths;
import ro.myfinance.taxpayments.domain.ParsedDeclaration;
import ro.myfinance.taxpayments.domain.PaymentLine;
import ro.myfinance.taxpayments.domain.TaxCategory;
import ro.myfinance.taxpayments.domain.TaxObligation;

/**
 * Turns a set of parsed declarations into payment lines. Each obligation resolves to a treasury IBAN
 * via its category (the per-residence settings); obligations sharing an IBAN are summed into one line —
 * so pointing impozit/CAS/CASS at the same "cont unic" IBAN reproduces the single "Contribuții sociale"
 * line, while CAM, TVA intern and TVA extern (different IBANs) stay separate.
 */
@Component
public class PaymentCalculator {

    private static final class Bucket {
        BigDecimal amount = BigDecimal.ZERO;
        final Set<TaxCategory> categories = new LinkedHashSet<>();
        LocalDate scadenta;
        YearMonth period;
    }

    /**
     * @param declarations  the parsed declarations for one company + period
     * @param ibanByCategory the resolved IBAN per category (from residence treasury settings); a category
     *                       with no IBAN configured is grouped under an empty key so it surfaces as unconfigured
     * @return one payment line per distinct IBAN, in first-seen order
     */
    public List<PaymentLine> compute(List<ParsedDeclaration> declarations,
                                     Map<TaxCategory, String> ibanByCategory) {
        Map<String, Bucket> byIban = new LinkedHashMap<>();
        for (ParsedDeclaration d : declarations) {
            for (TaxObligation o : d.obligations()) {
                if (o.amount().signum() <= 0) {
                    continue;
                }
                String iban = ibanByCategory.getOrDefault(o.category(), "");
                if (iban == null) {
                    iban = "";
                }
                Bucket b = byIban.computeIfAbsent(iban, k -> new Bucket());
                b.amount = b.amount.add(o.amount());
                b.categories.add(o.category());
                b.period = d.period();
                if (b.scadenta == null || (o.scadenta() != null && o.scadenta().isBefore(b.scadenta))) {
                    b.scadenta = o.scadenta();
                }
            }
        }
        List<PaymentLine> lines = new ArrayList<>();
        for (Map.Entry<String, Bucket> e : byIban.entrySet()) {
            Bucket b = e.getValue();
            lines.add(new PaymentLine(e.getKey(), b.amount, new ArrayList<>(b.categories),
                    explanation(b.categories, b.period), b.scadenta));
        }
        return lines;
    }

    /** Human explanation for a grouped line, mirroring the accountant's wording. */
    static String explanation(Set<TaxCategory> categories, YearMonth period) {
        String when = monthYear(period);
        if (categories.contains(TaxCategory.TVA_EXTERN)) {
            return "TVA extern " + when;
        }
        if (categories.contains(TaxCategory.TVA)) {
            return "TVA " + when;
        }
        if (categories.contains(TaxCategory.CAS) || categories.contains(TaxCategory.CASS)) {
            return "Contribuții sociale " + when;
        }
        if (categories.contains(TaxCategory.CAM)) {
            return "CAM luna " + when;
        }
        return "Impozite " + when;
    }

    static String monthYear(YearMonth period) {
        if (period == null) {
            return "";
        }
        // Dash-joined form ("Martie-2026") is the tax-payment explanation convention (see PaymentCalculatorTest);
        // the space-joined form for subjects/bodies is RomanianMonths.monthYear.
        return RomanianMonths.name(period.getMonthValue()) + "-" + period.getYear();
    }
}
