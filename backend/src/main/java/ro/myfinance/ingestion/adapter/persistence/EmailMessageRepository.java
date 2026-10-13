package ro.myfinance.ingestion.adapter.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.ingestion.domain.EmailMessage;

public interface EmailMessageRepository extends JpaRepository<EmailMessage, UUID> {

    Optional<EmailMessage> findByConnectionIdAndGmailMessageId(UUID connectionId, String gmailMessageId);
}
