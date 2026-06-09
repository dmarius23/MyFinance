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
        if (desc.contains("comision") || desc.contains("fee") || partner.contains("netopia")) {
            return new Result(false, DocCategory.FEE);
        }
        if (desc.contains("leasing")) {
            return new Result(true, DocCategory.LEASING);
        }
        return new Result(true, DocCategory.SUPPLIER);
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
