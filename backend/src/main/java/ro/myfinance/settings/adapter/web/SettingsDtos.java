package ro.myfinance.settings.adapter.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import ro.myfinance.settings.domain.CountyTreasuryAccount;
import ro.myfinance.settings.domain.GeneralSettings;

public final class SettingsDtos {

    private SettingsDtos() {
    }

    public record SettingsResponse(BigDecimal vatRate) {
        public static SettingsResponse from(GeneralSettings s) {
            return new SettingsResponse(s.getVatRate());
        }
    }

    public record UpdateVatRateRequest(@NotNull BigDecimal vatRate) {
    }

    public record CreateCountyTreasuryRequest(@NotBlank String county, @NotBlank String taxType,
                                              @NotBlank String iban, String label) {
    }

    public record CountyTreasuryResponse(UUID id, String county, String taxType, String iban, String label) {
        public static CountyTreasuryResponse from(CountyTreasuryAccount a) {
            return new CountyTreasuryResponse(a.getId(), a.getCounty(), a.getTaxType(),
                    a.getIban(), a.getLabel());
        }
    }
}
