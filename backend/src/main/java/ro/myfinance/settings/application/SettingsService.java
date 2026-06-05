package ro.myfinance.settings.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.settings.adapter.persistence.CountyTreasuryAccountRepository;
import ro.myfinance.settings.adapter.persistence.GeneralSettingsRepository;
import ro.myfinance.settings.domain.CountyTreasuryAccount;
import ro.myfinance.settings.domain.GeneralSettings;

/**
 * Tenant-level general settings: VAT rate and the county/tax-type treasury-account registry.
 * All reads/writes are RLS-scoped; tenant_id always comes from {@link TenantContext}.
 */
@Service
@Transactional
public class SettingsService {

    private final GeneralSettingsRepository settings;
    private final CountyTreasuryAccountRepository treasuryAccounts;

    public SettingsService(GeneralSettingsRepository settings,
                           CountyTreasuryAccountRepository treasuryAccounts) {
        this.settings = settings;
        this.treasuryAccounts = treasuryAccounts;
    }

    /**
     * Returns the tenant's settings, creating a default row (vat_rate = 21.00) if none exists yet.
     * NOT read-only because it may persist a new row.
     */
    public GeneralSettings getSettings() {
        UUID tenantId = currentTenant();
        return settings.findById(tenantId)
                .orElseGet(() -> settings.save(new GeneralSettings(tenantId)));
    }

    public GeneralSettings updateVatRate(BigDecimal rate) {
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("VAT rate must be between 0 and 100");
        }
        GeneralSettings s = getSettings();
        s.setVatRate(rate);
        return s;
    }

    @Transactional(readOnly = true)
    public List<CountyTreasuryAccount> listTreasuryAccounts() {
        return treasuryAccounts.findAll();
    }

    public CountyTreasuryAccount addTreasuryAccount(String county, String taxType, String iban, String label) {
        if (treasuryAccounts.existsByCountyAndTaxType(county, taxType)) {
            throw new ConflictException(
                    "A treasury account for " + county + " / " + taxType + " already exists");
        }
        return treasuryAccounts.save(
                new CountyTreasuryAccount(currentTenant(), county, taxType, iban, label));
    }

    public void deleteTreasuryAccount(UUID id) {
        CountyTreasuryAccount account = treasuryAccounts.findById(id)
                .orElseThrow(() -> new NotFoundException("Treasury account not found: " + id));
        treasuryAccounts.delete(account);
    }

    private UUID currentTenant() {
        return TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to request"));
    }
}
