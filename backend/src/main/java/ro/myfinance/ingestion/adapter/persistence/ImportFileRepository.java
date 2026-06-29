package ro.myfinance.ingestion.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.ingestion.domain.ImportFile;

public interface ImportFileRepository extends JpaRepository<ImportFile, UUID> {

    Optional<ImportFile> findByConnectionIdAndSourceRef(UUID connectionId, String sourceRef);

    boolean existsByConnectionIdAndContentSha256(UUID connectionId, String contentSha256);

    List<ImportFile> findByConnectionIdOrderByCreatedAtDesc(UUID connectionId);
}
