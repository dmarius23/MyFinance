package ro.myfinance.settings.adapter.web;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.settings.adapter.web.SettingsDtos.SettingsResponse;
import ro.myfinance.settings.adapter.web.SettingsDtos.TreasuryResponse;
import ro.myfinance.settings.adapter.web.SettingsDtos.UpdateSenderEmailRequest;
import ro.myfinance.settings.application.PlatformRatesService;
import ro.myfinance.settings.application.PlatformTreasuryService;
import ro.myfinance.settings.application.SettingsService;
import ro.myfinance.settings.domain.TaxRateCategory;

/**
 * Tenant-level general settings. TENANT_ADMIN only. Tax rates and treasury accounts are GLOBAL,
 * SUPER_ADMIN-managed reference data (see {@link PlatformRatesService} / {@link PlatformTreasuryService})
 * and are exposed here read-only, resolved for today; only {@code sender_email} is editable per-tenant.
 */
@RestController
@RequestMapping("/api/v1/settings")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class SettingsController {

    private final SettingsService service;
    private final PlatformRatesService rates;
    private final PlatformTreasuryService treasury;

    public SettingsController(SettingsService service, PlatformRatesService rates,
                              PlatformTreasuryService treasury) {
        this.service = service;
        this.rates = rates;
        this.treasury = treasury;
    }

    @GetMapping
    public SettingsResponse getSettings() {
        LocalDate today = LocalDate.now();
        return new SettingsResponse(
                rates.rateFor(TaxRateCategory.VAT, today).orElse(null),
                rates.rateFor(TaxRateCategory.MICRO, today).orElse(null),
                rates.rateFor(TaxRateCategory.PROFIT, today).orElse(null),
                service.getSettings().getSenderEmail());
    }

    @PutMapping
    public SettingsResponse updateSenderEmail(@Valid @RequestBody UpdateSenderEmailRequest request) {
        service.updateSenderEmail(request.senderEmail());
        return getSettings();
    }

    /** The treasury accounts in force today, read-only (managed globally by SUPER_ADMIN). */
    @GetMapping("/treasury-accounts")
    public List<TreasuryResponse> listTreasury() {
        return treasury.listEffective(LocalDate.now()).stream().map(TreasuryResponse::from).toList();
    }
}
