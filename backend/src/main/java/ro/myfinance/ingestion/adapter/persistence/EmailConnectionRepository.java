package ro.myfinance.ingestion.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.ingestion.domain.EmailConnection;

public interface EmailConnectionRepository extends JpaRepository<EmailConnection, UUID> {

    List<EmailConnection> findByOrderByCreatedAtDesc();

    List<EmailConnection> findByStatus(String status);

    Optional<EmailConnection> findByOauthStateHash(String oauthStateHash);
}
