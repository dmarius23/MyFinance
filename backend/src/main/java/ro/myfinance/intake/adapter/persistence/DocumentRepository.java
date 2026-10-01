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

    /** Duplicate check for a fresh upload (no persisted document to exclude yet). Tenant-scoped by RLS. */
    boolean existsByCompanyIdAndPeriodMonthAndTypeAndContentSha256(
            UUID companyId, LocalDate periodMonth, DocumentType type, String contentSha256);

    /** Duplicate check for a re-analysis (move/change-type) — exclude the document being re-evaluated. */
    boolean existsByCompanyIdAndPeriodMonthAndTypeAndContentSha256AndIdNot(
            UUID companyId, LocalDate periodMonth, DocumentType type, String contentSha256, UUID id);

    /**
     * The identical-content group (same company + period + type + hash), oldest first. Used to re-evaluate
     * the DUPLICATE flags after one of the copies is deleted: only the oldest survivor is the original.
     */
    List<Document> findByCompanyIdAndPeriodMonthAndTypeAndContentSha256OrderByUploadedAtAsc(
            UUID companyId, LocalDate periodMonth, DocumentType type, String contentSha256);
}
