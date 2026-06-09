package ro.myfinance.access.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.access.domain.RepresentativeLink;

public interface RepresentativeLinkRepository extends JpaRepository<RepresentativeLink, UUID> {

    List<RepresentativeLink> findByCompanyId(UUID companyId);
}
