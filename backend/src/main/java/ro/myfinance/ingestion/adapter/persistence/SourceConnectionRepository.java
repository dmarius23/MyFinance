package ro.myfinance.ingestion.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.ingestion.domain.SourceConnection;

public interface SourceConnectionRepository extends JpaRepository<SourceConnection, UUID> {

    List<SourceConnection> findByOrderByCreatedAtDesc();

    /** The current tenant's write-enabled connections for a provider (RLS scopes to the tenant). */
    List<SourceConnection> findByProviderAndWriteEnabledTrue(String provider);
}
