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

    public GeneralSettings updateRates(BigDecimal vatRate, BigDecimal microRate, BigDecimal profitRate,
                                       String senderEmail) {
        GeneralSettings s = getSettings();
        s.setVatRate(requirePercent(vatRate, "VAT"));
        s.setMicroRate(requirePercent(microRate, "Micro"));
        s.setProfitRate(requirePercent(profitRate, "Profit"));
        s.setSenderEmail(senderEmail == null || senderEmail.isBlank() ? null : senderEmail.trim());
        return s;
    }

    /** The accounting firm's outbound From address (null if not configured yet). */
    @Transactional(readOnly = true)
    public String senderEmail() {
        return TenantContext.tenantId().flatMap(settings::findById)
                .map(GeneralSettings::getSenderEmail).orElse(null);
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

    /** Create a residence row with its five per-category IBANs. One row per residence. */
    public ResidenceTreasuryAccount addTreasuryAccount(String residence, String ibanCam, String ibanImpozite,
                                                       String ibanCass, String ibanCas, String ibanTva) {
        if (residence == null || residence.isBlank()) {
            throw new IllegalArgumentException("Residence is required");
        }
        if (treasuryAccounts.existsByResidence(residence)) {
            throw new ConflictException("A treasury entry for " + residence + " already exists");
        }
        ResidenceTreasuryAccount account = new ResidenceTreasuryAccount(currentTenant(), residence);
        account.setIbans(ibanCam, ibanImpozite, ibanCass, ibanCas, ibanTva);
        return treasuryAccounts.save(account);
    }

    /** Update the five IBANs of an existing residence row (residence itself is immutable). */
    public ResidenceTreasuryAccount updateTreasuryAccount(UUID id, String ibanCam, String ibanImpozite,
                                                          String ibanCass, String ibanCas, String ibanTva) {
        ResidenceTreasuryAccount account = treasuryAccounts.findById(id)
                .orElseThrow(() -> new NotFoundException("Treasury entry not found: " + id));
        account.setIbans(ibanCam, ibanImpozite, ibanCass, ibanCas, ibanTva);
        return account;
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
