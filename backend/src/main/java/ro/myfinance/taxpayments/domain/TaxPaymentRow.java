package ro.myfinance.taxpayments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the monthly Tax &amp; Payments list: a company (name + CUI + fiscal residence), the
 * declarations it has uploaded for the period (one cell per declaration), and whether/when an email
 * was last sent.
 *
 * <p>{@code owesD112}/{@code owesD300} say whether the company is expected to file that declaration
 * (D112 only with employees, D300 only for VAT payers; D100 is owed by all). The list uses them to show
 * "N/A" instead of a "missing" chip for a type the company never has to file.
 */
public record TaxPaymentRow(UUID companyId, String companyName, String cui, String residence,
                            List<DeclarationCell> declarations, boolean owesD112, boolean owesD300,
                            Instant lastEmailAt, int emailCount,
                            Instant lastWhatsappAt, int whatsappCount) {

    /**
     * One fiscal obligation (creanță) shown as a line in its declaration-type column: the ANAF budget
     * {@code cod}, its short {@code label} (null when unmapped), and the amount. {@code declarationId}
     * ties it back to the uploaded document (for email selection); several obligations can share one.
     */
    public record DeclarationCell(UUID declarationId, DeclarationType type, String cod, String label,
                                  BigDecimal amount, boolean mismatch) {
    }
}
