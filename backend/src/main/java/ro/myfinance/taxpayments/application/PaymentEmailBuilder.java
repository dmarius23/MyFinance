package ro.myfinance.taxpayments.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ro.myfinance.taxpayments.domain.PaymentLine;
import ro.myfinance.taxpayments.domain.TaxCategory;

/**
 * Renders the two system-owned blocks for the state-payment email and assembles the variable map the
 * template renderer consumes. Money figures are never invented: they come straight from the itemized
 * declaration obligations. Formatting stays here, never in a template — a tenant may reposition the
 * account list but must never be able to reformat an IBAN line or an amount.
 */
@Component
public class PaymentEmailBuilder {

    private static final DateTimeFormatter SCADENTA = DateTimeFormatter.ofPattern("d MMMM yyyy");

    /** The summary list: one labelled amount per payment line. No trailing newline — it is one block. */
    public String summaryBlock(List<PaymentLine> lines) {
        return lines.stream()
                .map(l -> shortLabel(l.categories()) + ": " + plain(l))
                .collect(Collectors.joining("\n"));
    }

    /**
     * The per-IBAN detail list. Each group is preceded by a blank line, which is what separates the
     * groups from each other and from the {@code Conturi:} heading above them.
     */
    public String accountsBlock(List<PaymentLine> lines, String treasury, String cui) {
        List<String> out = new ArrayList<>();
        for (PaymentLine l : lines) {
            out.add("");
            out.add("- " + plain(l) + " lei in contul " + l.iban());
            out.add("- Beneficiar: " + orEmpty(treasury));
            out.add("- CUI Beneficiar: " + orEmpty(cui));
            out.add("- Explicație: " + l.explanation());
            if (l.scadenta() != null) {
                out.add("- Scadență: " + formatRo(l.scadenta()));
            }
        }
        return String.join("\n", out);
    }

    /**
     * Everything a {@code TAX} template may reference. Money stays formatted here, never in a template.
     *
     * <p>{@code authoritativeTotal} is {@link ro.myfinance.taxpayments.application.TaxPaymentService.Computation#total()}
     * — the full sum of every payable obligation, including categories whose treasury IBAN is not yet
     * configured. Using the lines-only sum would understate the state liability when any category is
     * unconfigured, making {@code {{totalToPay}}} disagree with the in-app notification's figure.
     */
    public Map<String, String> values(String companyName, String cui, YearMonth period,
                                      String treasury, List<PaymentLine> lines,
                                      java.math.BigDecimal authoritativeTotal,
                                      String senderName, String firmName) {
        Map<String, String> v = new HashMap<>();
        v.put("companyName", orEmpty(companyName));
        v.put("cui", orEmpty(cui));
        v.put("month", ro.myfinance.common.i18n.RomanianMonths.monthYear(period));
        v.put("treasury", orEmpty(treasury));
        v.put("totalToPay", authoritativeTotal.stripTrailingZeros().toPlainString());
        v.put("paymentSummary", summaryBlock(lines));
        v.put("paymentAccounts", accountsBlock(lines, treasury, cui));
        v.put("signOff", ro.myfinance.common.email.SignOff.names(senderName, firmName));
        return v;
    }

    static String plain(PaymentLine l) {
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

    private static String formatRo(LocalDate d) {
        return d.format(SCADENTA.withLocale(java.util.Locale.forLanguageTag("ro")));
    }

    /** Null or blank renders as empty — never the literal "null", never stray whitespace, in a client's email. */
    private static String orEmpty(String value) {
        return value == null || value.isBlank() ? "" : value;
    }
}
