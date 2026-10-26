package ro.myfinance.ingestion.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.ingestion.domain.ImportFile;

public interface ImportFileRepository extends JpaRepository<ImportFile, UUID> {

    Optional<ImportFile> findByConnectionIdAndSourceRef(UUID connectionId, String sourceRef);

    // A content-hash dedupe used to live here, scoped to one connection + company + period. Removed: it
    // could not see copies that arrived manually, by email, or through the firm's OTHER Drive connection
    // (DECLARATIONS and ACCOUNTING are separate). DocumentService now dedupes centrally against the
    // document table, per company, across all three ingestion sources.

    List<ImportFile> findByConnectionIdOrderByCreatedAtDesc(UUID connectionId);

    /** Files that did NOT import for one company + month — surfaced on the screen where the accountant
     *  looks for them, so a locked or unreadable file is visibly missing instead of silently absent. */
    List<ImportFile> findByCompanyIdAndPeriodMonthAndStatusNotOrderByCreatedAtDesc(
            UUID companyId, java.time.LocalDate periodMonth, String status);
}
