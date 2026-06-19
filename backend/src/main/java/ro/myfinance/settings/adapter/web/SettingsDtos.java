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

    public record CreateTreasuryRequest(@NotBlank String residence, java.util.List<String> taxTypes,
                                        @NotBlank String iban, String label) {
    }

    public record TreasuryResponse(UUID id, String residence, java.util.List<String> taxTypes,
                                   String iban, String label) {
        public static TreasuryResponse from(ResidenceTreasuryAccount a) {
            return new TreasuryResponse(a.getId(), a.getResidence(), a.getTaxTypes(),
                    a.getIban(), a.getLabel());
        }
    }
}
