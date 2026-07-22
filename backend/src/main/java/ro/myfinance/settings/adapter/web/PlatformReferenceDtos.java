package ro.myfinance.settings.adapter.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import ro.myfinance.settings.domain.PlatformTaxRate;
import ro.myfinance.settings.domain.PlatformTreasuryAccount;
import ro.myfinance.settings.domain.TaxRateCategory;

/** DTOs for the SUPER_ADMIN global-reference admin API (effective-dated rates + treasury). */
public final class PlatformReferenceDtos {

    private PlatformReferenceDtos() {
    }

    public record TaxRateResponse(UUID id, TaxRateCategory category, BigDecimal rate, LocalDate validFrom) {
        public static TaxRateResponse from(PlatformTaxRate r) {
            return new TaxRateResponse(r.getId(), r.getCategory(), r.getRate(), r.getValidFrom());
        }
    }

    public record CreateTaxRateRequest(@NotNull TaxRateCategory category, @NotNull BigDecimal rate,
                                       @NotNull LocalDate validFrom) {
    }

    public record UpdateTaxRateRequest(@NotNull BigDecimal rate) {
    }

    public record TreasuryResponse(UUID id, String residence, LocalDate validFrom, String ibanCam,
                                   String ibanImpozite, String ibanCass, String ibanCas, String ibanTva,
                                   String ibanTvaExtern) {
        public static TreasuryResponse from(PlatformTreasuryAccount a) {
            return new TreasuryResponse(a.getId(), a.getResidence(), a.getValidFrom(), a.getIbanCam(),
                    a.getIbanImpozite(), a.getIbanCass(), a.getIbanCas(), a.getIbanTva(), a.getIbanTvaExtern());
        }
    }

    public record CreateTreasuryRequest(@NotBlank String residence, @NotNull LocalDate validFrom,
                                        String ibanCam, String ibanImpozite, String ibanCass,
                                        String ibanCas, String ibanTva, String ibanTvaExtern) {
    }

    public record UpdateTreasuryRequest(String ibanCam, String ibanImpozite, String ibanCass,
                                        String ibanCas, String ibanTva, String ibanTvaExtern) {
    }
}
