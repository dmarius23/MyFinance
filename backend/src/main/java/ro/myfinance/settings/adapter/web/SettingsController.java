package ro.myfinance.settings.adapter.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.settings.adapter.web.SettingsDtos.CreateTreasuryRequest;
import ro.myfinance.settings.adapter.web.SettingsDtos.SettingsResponse;
import ro.myfinance.settings.adapter.web.SettingsDtos.TreasuryResponse;
import ro.myfinance.settings.adapter.web.SettingsDtos.UpdateTreasuryRequest;
import ro.myfinance.settings.adapter.web.SettingsDtos.UpdateRatesRequest;
import ro.myfinance.settings.application.SettingsService;

/** Tenant-level general settings. TENANT_ADMIN only. */
@RestController
@RequestMapping("/api/v1/settings")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class SettingsController {

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping
    public SettingsResponse getSettings() {
        return SettingsResponse.from(service.getSettings());
    }

    @PutMapping
    public SettingsResponse updateRates(@Valid @RequestBody UpdateRatesRequest request) {
        return SettingsResponse.from(service.updateRates(request.vatRate(), request.microRate(), request.profitRate()));
    }

    @GetMapping("/treasury-accounts")
    public List<TreasuryResponse> listTreasury() {
        return service.listTreasuryAccounts().stream().map(TreasuryResponse::from).toList();
    }

    @PostMapping("/treasury-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public TreasuryResponse addTreasury(@Valid @RequestBody CreateTreasuryRequest r) {
        return TreasuryResponse.from(service.addTreasuryAccount(r.residence(), r.ibanCam(), r.ibanImpozite(),
                r.ibanCass(), r.ibanCas(), r.ibanTva()));
    }

    @PutMapping("/treasury-accounts/{id}")
    public TreasuryResponse updateTreasury(@PathVariable UUID id, @Valid @RequestBody UpdateTreasuryRequest r) {
        return TreasuryResponse.from(service.updateTreasuryAccount(id, r.ibanCam(), r.ibanImpozite(),
                r.ibanCass(), r.ibanCas(), r.ibanTva()));
    }

    @DeleteMapping("/treasury-accounts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTreasury(@PathVariable UUID id) {
        service.deleteTreasuryAccount(id);
    }
}
