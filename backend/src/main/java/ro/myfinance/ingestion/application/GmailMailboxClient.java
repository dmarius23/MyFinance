package ro.myfinance.ingestion.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import ro.myfinance.ingestion.domain.EmailConnection;

/**
 * Gmail boundary. The production adapter will use Gmail API + Pub/Sub; the ingestion service remains
 * independent of Google SDK classes and can be tested with an in-memory implementation.
 */
public interface GmailMailboxClient {

    WatchRegistration watch(EmailConnection connection);

    /**
     * Revokes the provider grant when possible. Implementations may treat an already revoked grant as
     * success so disconnect remains idempotent.
     */
    default void revoke(EmailConnection connection) {
    }

    List<MessageRef> changes(EmailConnection connection, String startHistoryId);

    List<MessageRef> backfill(EmailConnection connection, LocalDate fromInclusive, LocalDate toExclusive);

    String currentHistoryId(EmailConnection connection);

    byte[] attachment(EmailConnection connection, MessageRef message, AttachmentRef attachment);

    record WatchRegistration(String historyId, Instant expiresAt) {
    }

    record MessageRef(String gmailMessageId, String threadId, String senderEmail, String subject,
                      Instant receivedAt, List<AttachmentRef> attachments) {
    }

    record AttachmentRef(String gmailAttachmentId, String filename, String contentType,
                         String inlineData) {
    }
}
