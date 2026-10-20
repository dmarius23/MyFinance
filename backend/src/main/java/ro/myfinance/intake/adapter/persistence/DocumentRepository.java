package ro.myfinance.intake.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DocumentType;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByCompanyIdOrderByUploadedAtDesc(UUID companyId);

    List<Document> findByCompanyIdAndPeriodMonthOrderByUploadedAtDesc(UUID companyId, LocalDate periodMonth);

    java.util.List<Document> findByPeriodMonth(java.time.LocalDate periodMonth);

    long countByCompanyIdAndPeriodMonth(UUID companyId, LocalDate periodMonth);

    /**
     * Duplicate check for a fresh upload (no persisted document to exclude yet). Tenant-scoped by RLS.
     *
     * <p>Keyed on company + hash only, deliberately NOT on period or type: those are inferred per
     * ingestion path (a manual upload uses the month the user picked, a Drive import derives it from the
     * filename/path/content/modified date; the type comes from adaptive classification), so including them
     * would let the same bytes be stored twice under a different guess with neither copy flagged.
     */
    boolean existsByCompanyIdAndContentSha256(UUID companyId, String contentSha256);

    /** Duplicate check for a re-analysis (move/change-type) — exclude the document being re-evaluated. */
    boolean existsByCompanyIdAndContentSha256AndIdNot(UUID companyId, String contentSha256, UUID id);

    /**
     * The identical-content group (same company + hash), oldest first. Used to re-evaluate the DUPLICATE
     * flags after one of the copies is deleted: only the oldest survivor is the original.
     */
    List<Document> findByCompanyIdAndContentSha256OrderByUploadedAtAsc(UUID companyId, String contentSha256);
}
