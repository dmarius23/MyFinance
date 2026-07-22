package ro.myfinance.settings.application;

/**
 * The four treasury IBANs we care about, scraped from one ANAF treasury PDF (one fiscal residence):
 * the 55.03 "cont unic" account (shared by impozit/CAS/CASS), CAM, TVA intern and TVA extern. A row with
 * a non-null {@link #error} means that treasury could not be fetched/parsed — the other IBAN fields are
 * then null and the sync records it as an ERROR item rather than failing the whole run.
 */
public record TreasuryIbans(String county, String treasuryCode, String residence, String sourceUrl,
                            String iban5503, String ibanCam, String ibanTvaIntern, String ibanTvaExtern,
                            String error) {

    public boolean ok() {
        return error == null;
    }

    /** Whether at least one of the four target IBANs was found. */
    public boolean hasAnyIban() {
        return iban5503 != null || ibanCam != null || ibanTvaIntern != null || ibanTvaExtern != null;
    }

    public static TreasuryIbans error(String county, String treasuryCode, String sourceUrl, String error) {
        return new TreasuryIbans(county, treasuryCode, null, sourceUrl, null, null, null, null, error);
    }
}
