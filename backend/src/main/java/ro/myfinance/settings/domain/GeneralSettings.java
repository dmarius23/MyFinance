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

    @Column(name = "micro_rate", nullable = false)
    private BigDecimal microRate = new BigDecimal("3.00");

    @Column(name = "profit_rate", nullable = false)
    private BigDecimal profitRate = new BigDecimal("16.00");

    /** The accounting firm's outbound "From" email for all client emails. */
    @Column(name = "sender_email")
    private String senderEmail;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GeneralSettings() {
    }

    public GeneralSettings(UUID tenantId) {
        this.tenantId = tenantId;
        this.vatRate = new BigDecimal("21.00");
        this.microRate = new BigDecimal("3.00");
        this.profitRate = new BigDecimal("16.00");
    }

    public UUID getTenantId() { return tenantId; }
    public BigDecimal getVatRate() { return vatRate; }
    public void setVatRate(BigDecimal vatRate) { this.vatRate = vatRate; }
    public BigDecimal getMicroRate() { return microRate; }
    public void setMicroRate(BigDecimal microRate) { this.microRate = microRate; }
    public BigDecimal getProfitRate() { return profitRate; }
    public void setProfitRate(BigDecimal profitRate) { this.profitRate = profitRate; }
    public String getSenderEmail() { return senderEmail; }
    public void setSenderEmail(String senderEmail) { this.senderEmail = senderEmail; }
    public Instant getUpdatedAt() { return updatedAt; }
}
