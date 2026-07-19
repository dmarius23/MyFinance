package ro.myfinance.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A national tax rate, effective-dated. GLOBAL reference data — no {@code tenant_id}, no RLS
 * (see V35). A computation for a given period uses the row with the greatest {@code valid_from}
 * that is still {@code <=} the period, so a rate change (e.g. VAT 19 -> 21) never retroactively
 * alters an earlier period. Managed by SUPER_ADMIN; tenants read only.
 */
@Entity
@Table(name = "platform_tax_rate")
public class PlatformTaxRate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaxRateCategory category;

    @Column(nullable = false)
    private BigDecimal rate;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PlatformTaxRate() {
    }

    public PlatformTaxRate(TaxRateCategory category, BigDecimal rate, LocalDate validFrom) {
        this.category = category;
        this.rate = rate;
        this.validFrom = validFrom;
    }

    /** The rate value is editable in place (category + effective date are immutable). */
    public void setRate(BigDecimal rate) { this.rate = rate; }

    public UUID getId() { return id; }
    public TaxRateCategory getCategory() { return category; }
    public BigDecimal getRate() { return rate; }
    public LocalDate getValidFrom() { return validFrom; }
    public Instant getCreatedAt() { return createdAt; }
}
