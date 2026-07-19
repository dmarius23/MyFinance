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
import ro.myfinance.settings.adapter.web.PlatformReferenceDtos.CreateTaxRateRequest;
import ro.myfinance.settings.adapter.web.PlatformReferenceDtos.CreateTreasuryRequest;
import ro.myfinance.settings.adapter.web.PlatformReferenceDtos.TaxRateResponse;
import ro.myfinance.settings.adapter.web.PlatformReferenceDtos.TreasuryResponse;
import ro.myfinance.settings.adapter.web.PlatformReferenceDtos.UpdateTaxRateRequest;
import ro.myfinance.settings.adapter.web.PlatformReferenceDtos.UpdateTreasuryRequest;
import ro.myfinance.settings.application.PlatformReferenceAdminService;

/**
 * SUPER_ADMIN management of the global reference tables (national tax rates + treasury accounts).
 * Under {@code /api/v1/admin/**}, which {@code SecurityConfig} already restricts to SUPER_ADMIN; the
 * {@code @PreAuthorize} is a belt-and-suspenders declaration of the same intent.
 */
@RestController
@RequestMapping("/api/v1/admin/reference")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformReferenceController {

    private final PlatformReferenceAdminService service;

    public PlatformReferenceController(PlatformReferenceAdminService service) {
        this.service = service;
    }

    // ---- tax rates ------------------------------------------------------------------------------

    @GetMapping("/tax-rates")
    public List<TaxRateResponse> listTaxRates() {
        return service.listTaxRates().stream().map(TaxRateResponse::from).toList();
    }

    @PostMapping("/tax-rates")
    @ResponseStatus(HttpStatus.CREATED)
    public TaxRateResponse addTaxRate(@Valid @RequestBody CreateTaxRateRequest r) {
        return TaxRateResponse.from(service.addTaxRate(r.category(), r.rate(), r.validFrom()));
    }

    @PutMapping("/tax-rates/{id}")
    public TaxRateResponse updateTaxRate(@PathVariable UUID id, @Valid @RequestBody UpdateTaxRateRequest r) {
        return TaxRateResponse.from(service.updateTaxRate(id, r.rate()));
    }

    @DeleteMapping("/tax-rates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTaxRate(@PathVariable UUID id) {
        service.deleteTaxRate(id);
    }

    // ---- treasury accounts ----------------------------------------------------------------------

    @GetMapping("/treasury-accounts")
    public List<TreasuryResponse> listTreasury() {
        return service.listTreasuryAccounts().stream().map(TreasuryResponse::from).toList();
    }

    @PostMapping("/treasury-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public TreasuryResponse addTreasury(@Valid @RequestBody CreateTreasuryRequest r) {
        return TreasuryResponse.from(service.addTreasuryAccount(r.residence(), r.validFrom(), r.ibanCam(),
                r.ibanImpozite(), r.ibanCass(), r.ibanCas(), r.ibanTva()));
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
