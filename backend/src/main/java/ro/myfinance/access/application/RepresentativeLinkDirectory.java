package ro.myfinance.access.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.access.domain.RepresentativeLink;

/**
 * Read-only directory of representative↔company links, exposed to other modules so they don't reach into
 * the {@code access} module's persistence adapter. All lookups are RLS-scoped to the current tenant.
 */
@Service
@Transactional(readOnly = true)
public class RepresentativeLinkDirectory {

    private final RepresentativeLinkRepository links;

    public RepresentativeLinkDirectory(RepresentativeLinkRepository links) {
        this.links = links;
    }

    /** The representative links for one company. */
    public List<RepresentativeLink> findByCompanyId(UUID companyId) {
        return links.findByCompanyId(companyId);
    }

    /** The representative links for a set of companies, in one query. */
    public List<RepresentativeLink> findByCompanyIdIn(List<UUID> companyIds) {
        return links.findByCompanyIdIn(companyIds);
    }

    /** All companies the given representative is assigned to. */
    public List<RepresentativeLink> findByUserId(UUID userId) {
        return links.findByUserId(userId);
    }
}
