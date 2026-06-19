package ro.myfinance.settings.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.settings.adapter.persistence.GeneralSettingsRepository;
import ro.myfinance.settings.adapter.persistence.ResidenceTreasuryAccountRepository;
import ro.myfinance.settings.domain.GeneralSettings;
import ro.myfinance.settings.domain.ResidenceTreasuryAccount;

/**
 * Tenant-level general settings: tax rates and the residence/tax-type treasury-account registry.
 * All reads/writes are RLS-scoped; tenant_id always comes from {@link TenantContext}.
 */
@Service
@Transactional
public class SettingsService {

    private final GeneralSettingsRepository settings;
    private final ResidenceTreasuryAccountRepository treasuryAccounts;

    public SettingsService(GeneralSettingsRepository settings,
                           ResidenceTreasuryAccountRepository treasuryAccounts) {
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

    public GeneralSettings updateRates(BigDecimal vatRate, BigDecimal microRate, BigDecimal profitRate) {
        GeneralSettings s = getSettings();
        s.setVatRate(requirePercent(vatRate, "VAT"));
        s.setMicroRate(requirePercent(microRate, "Micro"));
        s.setProfitRate(requirePercent(profitRate, "Profit"));
        return s;
    }

    private static BigDecimal requirePercent(BigDecimal rate, String name) {
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException(name + " rate must be between 0 and 100");
        }
        return rate;
    }

    @Transactional(readOnly = true)
    public List<ResidenceTreasuryAccount> listTreasuryAccounts() {
        return treasuryAccounts.findAll();
    }

    public ResidenceTreasuryAccount addTreasuryAccount(String residence, List<String> taxTypes,
                                                       String iban, String label) {
        if (taxTypes == null || taxTypes.isEmpty()) {
            throw new IllegalArgumentException("At least one tax type is required");
        }
        if (treasuryAccounts.existsByResidenceAndIban(residence, iban)) {
            throw new ConflictException(
                    "A treasury account for " + residence + " / " + iban + " already exists");
        }
        return treasuryAccounts.save(
                new ResidenceTreasuryAccount(currentTenant(), residence, taxTypes, iban, label));
    }

    public void deleteTreasuryAccount(UUID id) {
        ResidenceTreasuryAccount account = treasuryAccounts.findById(id)
                .orElseThrow(() -> new NotFoundException("Treasury account not found: " + id));
        treasuryAccounts.delete(account);
    }

    private UUID currentTenant() {
        return TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to request"));
    }
}
