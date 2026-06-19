package ro.myfinance.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;

/**
 * A tenant-level treasury account: one IBAN for a fiscal residence (city/town) that covers one or
 * more tax types. Used by MOD-07 to resolve the IBAN when composing state-payment emails.
 */
@Entity
@Table(name = "residence_treasury_account")
public class ResidenceTreasuryAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String residence;

    @Column(nullable = false)
    private String iban;

    private String label;

    @Convert(converter = StringListConverter.class)
    @Column(name = "tax_types", nullable = false)
    private List<String> taxTypes;

    protected ResidenceTreasuryAccount() {
    }

    public ResidenceTreasuryAccount(UUID tenantId, String residence, List<String> taxTypes, String iban, String label) {
        this.tenantId = tenantId;
        this.residence = residence;
        this.taxTypes = taxTypes;
        this.iban = iban;
        this.label = label;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getResidence() { return residence; }
    public List<String> getTaxTypes() { return taxTypes; }
    public String getIban() { return iban; }
    public String getLabel() { return label; }
}
