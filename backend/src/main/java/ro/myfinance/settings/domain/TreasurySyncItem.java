package ro.myfinance.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One treasury within a {@link TreasurySyncRun}: the four IBANs scraped from its PDF and how they compare
 * to the live reference data ({@link SyncChange}). {@code ERROR} rows carry a reason and null IBANs.
 */
@Entity
@Table(name = "platform_treasury_sync_item")
public class TreasurySyncItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column
    private String county;

    @Column(name = "treasury_code")
    private String treasuryCode;

    @Column
    private String residence;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "iban_5503")
    private String iban5503;

    @Column(name = "iban_cam")
    private String ibanCam;

    @Column(name = "iban_tva_intern")
    private String ibanTvaIntern;

    @Column(name = "iban_tva_extern")
    private String ibanTvaExtern;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncChange change;

    @Column
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TreasurySyncItem() {
    }

    /** A parsed treasury with a computed change verdict. */
    public TreasurySyncItem(UUID runId, String county, String treasuryCode, String residence, String sourceUrl,
                            String iban5503, String ibanCam, String ibanTvaIntern, String ibanTvaExtern,
                            SyncChange change) {
        this.runId = runId;
        this.county = county;
        this.treasuryCode = treasuryCode;
        this.residence = residence;
        this.sourceUrl = sourceUrl;
        this.iban5503 = iban5503;
        this.ibanCam = ibanCam;
        this.ibanTvaIntern = ibanTvaIntern;
        this.ibanTvaExtern = ibanTvaExtern;
        this.change = change;
    }

    /** An ERROR item: a treasury that could not be fetched/parsed. */
    public static TreasurySyncItem error(UUID runId, String county, String treasuryCode, String sourceUrl,
                                         String error) {
        TreasurySyncItem item = new TreasurySyncItem(runId, county, treasuryCode, null, sourceUrl,
                null, null, null, null, SyncChange.ERROR);
        item.error = error;
        return item;
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public String getCounty() { return county; }
    public String getTreasuryCode() { return treasuryCode; }
    public String getResidence() { return residence; }
    public String getSourceUrl() { return sourceUrl; }
    public String getIban5503() { return iban5503; }
    public String getIbanCam() { return ibanCam; }
    public String getIbanTvaIntern() { return ibanTvaIntern; }
    public String getIbanTvaExtern() { return ibanTvaExtern; }
    public SyncChange getChange() { return change; }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
}
