package ro.myfinance.ingestion.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.ingestion.domain.ImportFile;

public interface ImportFileRepository extends JpaRepository<ImportFile, UUID> {

    Optional<ImportFile> findByConnectionIdAndSourceRef(UUID connectionId, String sourceRef);

    /** Content-hash dedupe scoped to one company + period — identical bytes in another month import separately. */
    boolean existsByConnectionIdAndCompanyIdAndPeriodMonthAndContentSha256AndStatus(
            UUID connectionId, UUID companyId, LocalDate periodMonth, String contentSha256, String status);

    List<ImportFile> findByConnectionIdOrderByCreatedAtDesc(UUID connectionId);

    /** Files that did NOT import for one company + month — surfaced on the screen where the accountant
     *  looks for them, so a locked or unreadable file is visibly missing instead of silently absent. */
    List<ImportFile> findByCompanyIdAndPeriodMonthAndStatusNotOrderByCreatedAtDesc(
            UUID companyId, java.time.LocalDate periodMonth, String status);
}
