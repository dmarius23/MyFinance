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

    protected TaxDeclaration() {
    }

    public TaxDeclaration(UUID tenantId, UUID companyId, LocalDate periodMonth, UUID documentId) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.periodMonth = periodMonth;
        this.documentId = documentId;
    }

    public void apply(DeclarationType type, String cui, BigDecimal declaredTotal,
                      BigDecimal computedTotal, boolean mismatch) {
        this.type = type;
        this.cui = cui;
        this.declaredTotal = declaredTotal;
        this.computedTotal = computedTotal;
        this.mismatch = mismatch;
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
}
