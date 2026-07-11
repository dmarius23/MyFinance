package ro.myfinance.access.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.access.domain.RepresentativeLink;

public interface RepresentativeLinkRepository extends JpaRepository<RepresentativeLink, UUID> {

    List<RepresentativeLink> findByCompanyId(UUID companyId);

    /** Links for a set of companies in one query (dashboard: representatives per company). */
    List<RepresentativeLink> findByCompanyIdIn(List<UUID> companyIds);

    /** All companies the given representative is assigned to (tenant-scoped via RLS). */
    List<RepresentativeLink> findByUserId(UUID userId);

    boolean existsByUserIdAndCompanyId(UUID userId, UUID companyId);

    java.util.Optional<RepresentativeLink> findByUserIdAndCompanyId(UUID userId, UUID companyId);
}
