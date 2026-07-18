package ro.myfinance.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Treasury IBANs for one fiscal residence (city/town): a dedicated IBAN per tax category —
 * CAM, impozite (all income/profit/salary/dividend taxes), CASS, CAS, TVA. GLOBAL reference data
 * (no {@code tenant_id}, no RLS — see V35) and effective-dated: the row with the greatest
 * {@code valid_from <= period} wins. Managed by SUPER_ADMIN; tenants read only. Used by MOD-07 to
 * resolve the IBAN when composing state-payment emails.
 */
@Entity
@Table(name = "platform_treasury_account")
public class PlatformTreasuryAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
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

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PlatformTreasuryAccount() {
    }

    public PlatformTreasuryAccount(String residence, LocalDate validFrom) {
        this.residence = residence;
        this.validFrom = validFrom;
    }

    /** Replace all five IBANs (blank -> null). */
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
    public String getResidence() { return residence; }
    public String getIbanCam() { return ibanCam; }
    public String getIbanImpozite() { return ibanImpozite; }
    public String getIbanCass() { return ibanCass; }
    public String getIbanCas() { return ibanCas; }
    public String getIbanTva() { return ibanTva; }
    public LocalDate getValidFrom() { return validFrom; }
    public Instant getCreatedAt() { return createdAt; }
}
