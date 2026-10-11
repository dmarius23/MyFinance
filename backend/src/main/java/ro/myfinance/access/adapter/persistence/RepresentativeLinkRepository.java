package ro.myfinance.access.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Resolves a sender only through tenant-local representative links. The RLS policies on both
     * representative_link and app_user remain the final isolation boundary.
     */
    @Query("""
            select link from RepresentativeLink link, AppUser user
            where link.userId = user.id
              and lower(user.email) = lower(:email)
            """)
    List<RepresentativeLink> findByRepresentativeEmail(@Param("email") String email);
}
