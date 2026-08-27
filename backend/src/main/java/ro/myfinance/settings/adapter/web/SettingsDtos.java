package ro.myfinance.settings.adapter.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import ro.myfinance.settings.domain.PlatformTreasuryAccount;

public final class SettingsDtos {

    private SettingsDtos() {
    }

    /**
     * The tenant Settings view. Tax rates come from the global effective-dated reference tables
     * (read-only for the tenant, resolved for today) and may be null if not yet configured; only
     * {@code senderEmail} is per-tenant and editable.
     */
    public record SettingsResponse(BigDecimal vatRate, BigDecimal microRate, BigDecimal profitRate,
                                   String senderEmail, boolean autoSyncEnabled, int autoSyncHour) {
    }

    public record UpdateSenderEmailRequest(String senderEmail) {
    }

    /** Nightly auto-sync schedule: on/off + the local hour (0–23, Europe/Bucharest) it runs. */
    public record UpdateAutoSyncRequest(boolean enabled,
                                        @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(23) int hour) {
    }

    /** Read-only treasury row shown on the tenant Settings page (the account in force today). */
    public record TreasuryResponse(String residence, LocalDate validFrom, String ibanCam, String ibanImpozite,
                                   String ibanCass, String ibanCas, String ibanTva, String ibanTvaExtern) {
        public static TreasuryResponse from(PlatformTreasuryAccount a) {
            return new TreasuryResponse(a.getResidence(), a.getValidFrom(), a.getIbanCam(), a.getIbanImpozite(),
                    a.getIbanCass(), a.getIbanCas(), a.getIbanTva(), a.getIbanTvaExtern());
        }
    }
}
