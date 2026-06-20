package ro.myfinance.taxpayments.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** A stored declaration for the manager modal: amounts, mismatch, and the wrong-party / outside-period flags. */
public record DeclarationView(UUID id, UUID documentId, DeclarationType type, BigDecimal computedTotal,
                             BigDecimal declaredTotal, boolean mismatch, String cui, boolean wrongParty,
                             boolean outsidePeriod, boolean duplicate) {

    public static DeclarationView from(TaxDeclaration d) {
        return new DeclarationView(d.getId(), d.getDocumentId(), d.getType(), d.getComputedTotal(),
                d.getDeclaredTotal(), d.isMismatch(), d.getCui(), d.isWrongParty(), d.isOutsidePeriod(),
                d.isDuplicate());
    }
}
