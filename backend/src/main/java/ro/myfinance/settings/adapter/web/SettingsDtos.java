package ro.myfinance.settings.adapter.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import ro.myfinance.settings.domain.GeneralSettings;
import ro.myfinance.settings.domain.ResidenceTreasuryAccount;

public final class SettingsDtos {

    private SettingsDtos() {
    }

    public record SettingsResponse(BigDecimal vatRate, BigDecimal microRate, BigDecimal profitRate) {
        public static SettingsResponse from(GeneralSettings s) {
            return new SettingsResponse(s.getVatRate(), s.getMicroRate(), s.getProfitRate());
        }
    }

    public record UpdateRatesRequest(@NotNull BigDecimal vatRate, @NotNull BigDecimal microRate,
                                     @NotNull BigDecimal profitRate) {
    }

    public record CreateTreasuryRequest(@NotBlank String residence, String ibanCam, String ibanImpozite,
                                        String ibanCass, String ibanCas, String ibanTva) {
    }

    public record UpdateTreasuryRequest(String ibanCam, String ibanImpozite, String ibanCass,
                                        String ibanCas, String ibanTva) {
    }

    public record TreasuryResponse(UUID id, String residence, String ibanCam, String ibanImpozite,
                                   String ibanCass, String ibanCas, String ibanTva) {
        public static TreasuryResponse from(ResidenceTreasuryAccount a) {
            return new TreasuryResponse(a.getId(), a.getResidence(), a.getIbanCam(), a.getIbanImpozite(),
                    a.getIbanCass(), a.getIbanCas(), a.getIbanTva());
        }
    }
}
