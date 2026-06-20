package ro.myfinance.taxpayments.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import ro.myfinance.taxpayments.domain.PaymentLine;
import ro.myfinance.taxpayments.domain.TaxCategory;

/**
 * Renders the Romanian state-payment email body from computed {@link PaymentLine}s — the plain-text
 * version of the accountant's template. Money figures are never invented: they come straight from the
 * itemized declaration obligations.
 */
@Component
public class PaymentEmailBuilder {

    private static final DateTimeFormatter SCADENTA = DateTimeFormatter.ofPattern("d MMMM yyyy");

    /**
     * @param companyName legal name of the client company
     * @param cui         the company's own CUI (it is the beneficiary in the treasury system)
     * @param period      the reporting period
     * @param treasury    beneficiary treasury office, e.g. "Trezoreria Cluj Napoca"
     * @param lines       computed payment lines
     */
    public String build(String companyName, String cui, YearMonth period, String treasury,
                        List<PaymentLine> lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bună ziua,\n\n");
        sb.append("Sumele de plată pentru luna ").append(monthYearSpace(period))
                .append(" aferente firmei ").append(companyName).append(" sunt următoarele:\n\n");
        for (PaymentLine l : lines) {
            sb.append(shortLabel(l.categories())).append(": ").append(plain(l)).append('\n');
        }
        sb.append("Conturi:\n");
        for (PaymentLine l : lines) {
            sb.append('\n');
            sb.append("- ").append(plain(l)).append(" lei in contul ").append(l.iban()).append('\n');
            sb.append("- Beneficiar: ").append(treasury).append('\n');
            sb.append("- CUI Beneficiar: ").append(cui).append('\n');
            sb.append("- Explicație: ").append(l.explanation()).append('\n');
            if (l.scadenta() != null) {
                sb.append("- Scadență: ").append(formatRo(l.scadenta())).append('\n');
            }
        }
        sb.append("\nO zi plăcută,\n");
        return sb.toString();
    }

    private static String plain(PaymentLine l) {
        return l.amount().stripTrailingZeros().toPlainString();
    }

    /** Short label used in both the summary block and as a fallback explanation. */
    static String shortLabel(Set<TaxCategory> categories) {
        if (categories.contains(TaxCategory.TVA)) {
            return "TVA";
        }
        if (categories.contains(TaxCategory.CAS) || categories.contains(TaxCategory.CASS)) {
            return "Contribuții sociale";
        }
        if (categories.contains(TaxCategory.CAM)) {
            return "CAM";
        }
        return "Impozite";
    }

    static String shortLabel(List<TaxCategory> categories) {
        return shortLabel(Set.copyOf(categories));
    }

    private static String monthYearSpace(YearMonth period) {
        return PaymentCalculator.monthYear(period).replace('-', ' ');
    }

    private static String formatRo(LocalDate d) {
        return d.format(SCADENTA.withLocale(java.util.Locale.forLanguageTag("ro")));
    }
}
