package ro.myfinance.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Treasury accounts for one fiscal residence (city/town): a dedicated IBAN per tax category —
 * CAM, impozite (all income/profit/salary/dividend taxes), CASS, CAS, TVA. Any column may be null
 * when that residence has no account for the category. Used by MOD-07 to resolve the IBAN when
 * composing state-payment emails.
 */
@Entity
@Table(name = "residence_treasury")
public class ResidenceTreasuryAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String residence;

    @Column(name = "iban_cam")
    private String ibanCam;

    @Column(name = "iban_impozite")
    private String ibanImpozite;

    @Column(name = "iban_cass")
    private String ibanCass;

    @Column(name = "iban_cas")
    private String ibanCas;

    @Column(name = "iban_tva")
    private String ibanTva;

    protected ResidenceTreasuryAccount() {
    }

    public ResidenceTreasuryAccount(UUID tenantId, String residence) {
        this.tenantId = tenantId;
        this.residence = residence;
    }

    /** Replace all five IBANs (blank → null). */
    public void setIbans(String cam, String impozite, String cass, String cas, String tva) {
        this.ibanCam = blankToNull(cam);
        this.ibanImpozite = blankToNull(impozite);
        this.ibanCass = blankToNull(cass);
        this.ibanCas = blankToNull(cas);
        this.ibanTva = blankToNull(tva);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getResidence() { return residence; }
    public String getIbanCam() { return ibanCam; }
    public String getIbanImpozite() { return ibanImpozite; }
    public String getIbanCass() { return ibanCass; }
    public String getIbanCas() { return ibanCas; }
    public String getIbanTva() { return ibanTva; }
}
