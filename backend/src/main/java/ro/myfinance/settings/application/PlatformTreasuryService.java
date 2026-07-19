package ro.myfinance.settings.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.settings.adapter.persistence.PlatformTreasuryAccountRepository;
import ro.myfinance.settings.domain.PlatformTreasuryAccount;

/**
 * Period-aware resolver for the global treasury accounts. GLOBAL, not tenant-scoped — the table
 * holds public national reference data (see V35). A period's account for a residence is the one
 * whose {@code valid_from} is the greatest value still {@code <=} the period.
 */
@Service
@Transactional(readOnly = true)
public class PlatformTreasuryService {

    private final PlatformTreasuryAccountRepository accounts;

    public PlatformTreasuryService(PlatformTreasuryAccountRepository accounts) {
        this.accounts = accounts;
    }

    /** The treasury account in force for {@code residence} at {@code period}, if any. */
    public Optional<PlatformTreasuryAccount> accountFor(String residence, LocalDate period) {
        if (residence == null || residence.isBlank() || period == null) {
            return Optional.empty();
        }
        return accounts.findTopByResidenceAndValidFromLessThanEqualOrderByValidFromDesc(residence, period);
    }

    /**
     * One account per residence — the row in force at {@code period} — for read-only display (e.g. the
     * tenant Settings page). Residences whose earliest {@code valid_from} is after {@code period} are
     * omitted. Ordered by residence.
     */
    public List<PlatformTreasuryAccount> listEffective(LocalDate period) {
        if (period == null) {
            return List.of();
        }
        // Rows come grouped by residence, newest effective date first; the first row per residence whose
        // valid_from is <= period is the effective one.
        List<PlatformTreasuryAccount> effective = new ArrayList<>();
        String lastResidence = null;
        for (PlatformTreasuryAccount a : accounts.findAllByOrderByResidenceAscValidFromDesc()) {
            if (a.getResidence().equals(lastResidence)) {
                continue; // already picked this residence's effective row
            }
            if (!a.getValidFrom().isAfter(period)) {
                effective.add(a);
                lastResidence = a.getResidence();
            }
        }
        return effective;
    }
}
