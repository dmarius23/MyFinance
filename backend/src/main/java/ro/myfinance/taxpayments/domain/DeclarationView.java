package ro.myfinance.taxpayments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A stored declaration for the manager modal: amounts, flags, and how many times it's been emailed. */
public record DeclarationView(UUID id, UUID documentId, DeclarationType type, BigDecimal computedTotal,
                             BigDecimal declaredTotal, boolean mismatch, String cui, boolean wrongParty,
                             boolean outsidePeriod, LocalDate declPeriod, boolean duplicate,
                             int sentCount, Instant lastSentAt) {

    public static DeclarationView from(TaxDeclaration d, int sentCount, Instant lastSentAt) {
        return new DeclarationView(d.getId(), d.getDocumentId(), d.getType(), d.getComputedTotal(),
                d.getDeclaredTotal(), d.isMismatch(), d.getCui(), d.isWrongParty(), d.isOutsidePeriod(),
                d.getDeclPeriod(), d.isDuplicate(), sentCount, lastSentAt);
    }
}
