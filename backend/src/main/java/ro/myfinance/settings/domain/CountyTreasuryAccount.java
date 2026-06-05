package ro.myfinance.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A tenant-level treasury account for a given county + tax type combination.
 * Used by MOD-07 to resolve the correct IBAN when composing state-payment emails.
 */
@Entity
@Table(name = "county_treasury_account")
public class CountyTreasuryAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String county;

    @Column(name = "tax_type", nullable = false)
    private String taxType;

    @Column(nullable = false)
    private String iban;

    private String label;

    protected CountyTreasuryAccount() {
    }

    public CountyTreasuryAccount(UUID tenantId, String county, String taxType, String iban, String label) {
        this.tenantId = tenantId;
        this.county = county;
        this.taxType = taxType;
        this.iban = iban;
        this.label = label;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getCounty() { return county; }
    public String getTaxType() { return taxType; }
    public String getIban() { return iban; }
    public String getLabel() { return label; }
}
