package ro.myfinance.taxpayments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A stored ANAF declaration: extracted summary kept per company/period, 1:1 with the uploaded PDF. */
@Entity
@Table(name = "tax_declaration")
public class TaxDeclaration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeclarationType type;

    private String cui;

    @Column(name = "declared_total")
    private BigDecimal declaredTotal;

    @Column(name = "computed_total", nullable = false)
    private BigDecimal computedTotal = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean mismatch;

    /** The declaration's own period from its XML (to detect a doc filed under the wrong month). */
    @Column(name = "decl_period")
    private LocalDate declPeriod;

    /** True when the declaration's CUI does not match the company it was uploaded for. */
    @Column(name = "wrong_party", nullable = false)
    private boolean wrongParty;

    /** True when another declaration of the same type already exists for this company + period. */
    @Column(nullable = false)
    private boolean duplicate;

    protected TaxDeclaration() {
    }

    public TaxDeclaration(UUID tenantId, UUID companyId, LocalDate periodMonth, UUID documentId) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.periodMonth = periodMonth;
        this.documentId = documentId;
    }

    public void apply(DeclarationType type, String cui, BigDecimal declaredTotal, BigDecimal computedTotal,
                      boolean mismatch, LocalDate declPeriod, boolean wrongParty, boolean duplicate) {
        this.type = type;
        this.cui = cui;
        this.declaredTotal = declaredTotal;
        this.computedTotal = computedTotal;
        this.mismatch = mismatch;
        this.declPeriod = declPeriod;
        this.wrongParty = wrongParty;
        this.duplicate = duplicate;
    }

    /** Outside the period it was filed under (its own period differs from the upload month). */
    public boolean isOutsidePeriod() {
        return declPeriod != null && !declPeriod.equals(periodMonth);
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public LocalDate getPeriodMonth() { return periodMonth; }
    public UUID getDocumentId() { return documentId; }
    public DeclarationType getType() { return type; }
    public String getCui() { return cui; }
    public BigDecimal getDeclaredTotal() { return declaredTotal; }
    public BigDecimal getComputedTotal() { return computedTotal; }
    public boolean isMismatch() { return mismatch; }
    public LocalDate getDeclPeriod() { return declPeriod; }
    public boolean isWrongParty() { return wrongParty; }
    public boolean isDuplicate() { return duplicate; }
}
