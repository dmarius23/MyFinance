package ro.myfinance.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Tenant-level configuration — one row per tenant, created lazily on first read.
 * {@code tenant_id} is both the PK and the tenant ownership identifier; RLS enforces isolation.
 */
@Entity
@Table(name = "general_settings")
public class GeneralSettings {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "vat_rate", nullable = false)
    private BigDecimal vatRate = new BigDecimal("21.00");

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GeneralSettings() {
    }

    public GeneralSettings(UUID tenantId) {
        this.tenantId = tenantId;
        this.vatRate = new BigDecimal("21.00");
    }

    public UUID getTenantId() { return tenantId; }
    public BigDecimal getVatRate() { return vatRate; }
    public void setVatRate(BigDecimal vatRate) { this.vatRate = vatRate; }
    public Instant getUpdatedAt() { return updatedAt; }
}
