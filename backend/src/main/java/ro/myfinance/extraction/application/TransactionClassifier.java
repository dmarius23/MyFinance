package ro.myfinance.extraction.application;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import ro.myfinance.extraction.domain.DocCategory;

/**
 * Deterministic base-rule classifier (no LLM): decides whether a transaction requires a supporting
 * document and assigns a category. Learned-rule and accountant overrides are applied by
 * ReconciliationService on top of this base result.
 */
@Component
public class TransactionClassifier {

    private static final Pattern TREASURY = Pattern.compile("^RO\\d{2}TREZ.*");

    public record Input(boolean credit, String partnerIban, String partnerName, String description,
                        String ownAccountIban, String companyName) {
    }

    public record Result(boolean requiresDocument, DocCategory category) {
    }

    public Result classify(Input in) {
        if (in.credit()) {
            return new Result(false, DocCategory.INCOME);
        }
        if (in.partnerIban() != null && TREASURY.matcher(in.partnerIban()).matches()) {
            return new Result(false, DocCategory.TAX);
        }
        if (isOwnTransfer(in)) {
            return new Result(false, DocCategory.OWN_TRANSFER);
        }
        String desc = ReconText.normalize(in.description());
        String partner = ReconText.normalize(in.partnerName());
        if (desc.contains("salariu") || desc.contains("salary")) {
            return new Result(false, DocCategory.SALARY);
        }
        // Bank fees / charges — commission ("comision operatiune/tranzactie"), account maintenance
        // ("intretinere cont"), or the Netopia processor. These are the bank's own lines, not supplier
        // purchases, so they need no supporting document. Checked in both the description and partner
        // name (the parser sometimes carries the label as the partner).
        if (containsAny(desc, "comision", "fee", "intretinere", "administrare cont")
                || containsAny(partner, "comision", "intretinere", "netopia")) {
            return new Result(false, DocCategory.FEE);
        }
        if (desc.contains("leasing")) {
            return new Result(true, DocCategory.LEASING);
        }
        // A debit with no counterparty at all (no partner name and no IBAN) is a bank-internal line —
        // typically a fee the statement didn't label — not a supplier payment, so no document is due.
        if (partner.isBlank() && isBlank(in.partnerIban())) {
            return new Result(false, DocCategory.FEE);
        }
        return new Result(true, DocCategory.SUPPLIER);
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean isOwnTransfer(Input in) {
        if (in.partnerIban() != null && in.partnerIban().equals(in.ownAccountIban())) {
            return true;
        }
        String company = ReconText.normalize(in.companyName());
        String partner = ReconText.normalize(in.partnerName());
        return !company.isBlank() && !partner.isBlank()
                && (partner.contains(company) || company.contains(partner));
    }
}
