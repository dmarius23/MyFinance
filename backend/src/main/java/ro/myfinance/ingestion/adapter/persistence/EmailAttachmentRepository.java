package ro.myfinance.ingestion.adapter.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.ingestion.domain.EmailAttachment;

public interface EmailAttachmentRepository extends JpaRepository<EmailAttachment, UUID> {

    Optional<EmailAttachment> findByMessageIdAndGmailAttachmentId(UUID messageId, String attachmentId);

    boolean existsByContentSha256AndStatus(String contentSha256, String status);
}
