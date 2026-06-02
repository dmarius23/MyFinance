package ro.myfinance.mod03_company.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * MOD-03 — a Treasury (Trezorerie) account per (tax_type, locality). Required before a state-pay
 * email can be sent for that tax (MOD-07 blocks send when missing).
 */
@Entity
@Table(name = "treasury_account")
public class TreasuryAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "tax_type", nullable = false)
    private String taxType;

    private String locality;

    @Column(nullable = false)
    private String iban;

    private String label;

    protected TreasuryAccount() {
    }

    public TreasuryAccount(UUID tenantId, UUID companyId, String taxType, String locality,
                           String iban, String label) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.taxType = taxType;
        this.locality = locality;
        this.iban = iban;
        this.label = label;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getTaxType() {
        return taxType;
    }

    public String getLocality() {
        return locality;
    }

    public String getIban() {
        return iban;
    }

    public String getLabel() {
        return label;
    }
}
