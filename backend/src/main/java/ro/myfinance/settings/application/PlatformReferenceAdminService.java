package ro.myfinance.settings.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.settings.adapter.persistence.PlatformTaxRateRepository;
import ro.myfinance.settings.adapter.persistence.PlatformTreasuryAccountRepository;
import ro.myfinance.settings.domain.PlatformTaxRate;
import ro.myfinance.settings.domain.PlatformTreasuryAccount;
import ro.myfinance.settings.domain.TaxRateCategory;

/**
 * SUPER_ADMIN management of the GLOBAL reference tables (national tax rates + treasury accounts).
 * These rows are effective-dated and shared by every tenant — there is no {@code tenant_id}/RLS, so
 * write authorization is enforced entirely at the web layer ({@code hasRole('SUPER_ADMIN')}). Reads
 * used by computation live in {@link PlatformRatesService} / {@link PlatformTreasuryService}; this
 * service is the append/edit/delete side plus the full history the admin screen lists.
 */
@Service
@Transactional
public class PlatformReferenceAdminService {

    private final PlatformTaxRateRepository rates;
    private final PlatformTreasuryAccountRepository treasury;

    public PlatformReferenceAdminService(PlatformTaxRateRepository rates,
                                         PlatformTreasuryAccountRepository treasury) {
        this.rates = rates;
        this.treasury = treasury;
    }

    // ---- tax rates ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PlatformTaxRate> listTaxRates() {
        return rates.findAllByOrderByCategoryAscValidFromDesc();
    }

    /** Add an effective-dated rate. One row per (category, valid_from). */
    public PlatformTaxRate addTaxRate(TaxRateCategory category, BigDecimal rate, LocalDate validFrom) {
        if (category == null) {
            throw new IllegalArgumentException("Category is required");
        }
        if (validFrom == null) {
            throw new IllegalArgumentException("Effective date is required");
        }
        requirePercent(rate);
        if (rates.existsByCategoryAndValidFrom(category, validFrom)) {
            throw new ConflictException("A " + category + " rate effective " + validFrom + " already exists");
        }
        return rates.save(new PlatformTaxRate(category, rate, validFrom));
    }

    /** Edit the value of an existing rate (category + effective date stay fixed). */
    public PlatformTaxRate updateTaxRate(UUID id, BigDecimal rate) {
        requirePercent(rate);
        PlatformTaxRate r = rates.findById(id)
                .orElseThrow(() -> new NotFoundException("Tax rate not found: " + id));
        r.setRate(rate);
        return r;
    }

    public void deleteTaxRate(UUID id) {
        PlatformTaxRate r = rates.findById(id)
                .orElseThrow(() -> new NotFoundException("Tax rate not found: " + id));
        rates.delete(r);
    }

    // ---- treasury accounts ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PlatformTreasuryAccount> listTreasuryAccounts() {
        return treasury.findAllByOrderByResidenceAscValidFromDesc();
    }

    /** Add an effective-dated treasury row for a residence. One row per (residence, valid_from). */
    public PlatformTreasuryAccount addTreasuryAccount(String residence, LocalDate validFrom, String ibanCam,
                                                      String ibanImpozite, String ibanCass, String ibanCas,
                                                      String ibanTva, String ibanTvaExtern) {
        if (residence == null || residence.isBlank()) {
            throw new IllegalArgumentException("Residence is required");
        }
        if (validFrom == null) {
            throw new IllegalArgumentException("Effective date is required");
        }
        if (treasury.existsByResidenceAndValidFrom(residence, validFrom)) {
            throw new ConflictException("A treasury entry for " + residence + " effective "
                    + validFrom + " already exists");
        }
        PlatformTreasuryAccount account = new PlatformTreasuryAccount(residence.trim(), validFrom);
        account.setIbans(ibanCam, ibanImpozite, ibanCass, ibanCas, ibanTva, ibanTvaExtern);
        return treasury.save(account);
    }

    /**
     * Add-or-update the effective-dated row for {@code (residence, validFrom)}. Used by the ANAF sync when
     * applying an approved diff, so re-applying the same run is idempotent (the second pass just re-sets the
     * same IBANs on the existing row instead of raising the {@code addTreasuryAccount} conflict).
     */
    public PlatformTreasuryAccount upsertTreasuryAccount(String residence, LocalDate validFrom, String ibanCam,
                                                         String ibanImpozite, String ibanCass, String ibanCas,
                                                         String ibanTva, String ibanTvaExtern) {
        if (residence == null || residence.isBlank()) {
            throw new IllegalArgumentException("Residence is required");
        }
        if (validFrom == null) {
            throw new IllegalArgumentException("Effective date is required");
        }
        PlatformTreasuryAccount account = treasury.findByResidenceAndValidFrom(residence.trim(), validFrom)
                .orElseGet(() -> new PlatformTreasuryAccount(residence.trim(), validFrom));
        account.setIbans(ibanCam, ibanImpozite, ibanCass, ibanCas, ibanTva, ibanTvaExtern);
        return treasury.save(account);
    }

    /** Update the IBANs of an existing row (residence and effective date are immutable). */
    public PlatformTreasuryAccount updateTreasuryAccount(UUID id, String ibanCam, String ibanImpozite,
                                                         String ibanCass, String ibanCas, String ibanTva,
                                                         String ibanTvaExtern) {
        PlatformTreasuryAccount account = treasury.findById(id)
                .orElseThrow(() -> new NotFoundException("Treasury entry not found: " + id));
        account.setIbans(ibanCam, ibanImpozite, ibanCass, ibanCas, ibanTva, ibanTvaExtern);
        return account;
    }

    public void deleteTreasuryAccount(UUID id) {
        PlatformTreasuryAccount account = treasury.findById(id)
                .orElseThrow(() -> new NotFoundException("Treasury entry not found: " + id));
        treasury.delete(account);
    }

    private static void requirePercent(BigDecimal rate) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Rate must be between 0 and 100");
        }
    }
}
