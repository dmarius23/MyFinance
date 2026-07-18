package ro.myfinance.settings.application;

import java.time.LocalDate;
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
}
