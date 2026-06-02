package ro.myfinance.mod02_access.adapter.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.mod02_access.domain.RepresentativeLink;

public interface RepresentativeLinkRepository
        extends JpaRepository<RepresentativeLink, RepresentativeLink.Key> {
}
