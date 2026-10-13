package ro.myfinance.ingestion.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.hash.ContentHash;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.access.application.RepresentativeLinkDirectory;
import ro.myfinance.ingestion.adapter.persistence.EmailAttachmentRepository;
import ro.myfinance.ingestion.adapter.persistence.EmailConnectionRepository;
import ro.myfinance.ingestion.adapter.persistence.EmailMessageRepository;
import ro.myfinance.ingestion.domain.EmailAttachment;
import ro.myfinance.ingestion.domain.EmailConnection;
import ro.myfinance.ingestion.domain.EmailMessage;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DocumentSource;
import ro.myfinance.notifications.application.NotificationService;

/**
 * Persists one Gmail message and imports its resolved attachments through the normal intake chokepoint.
 *
 * <p>The Gmail adapter is intentionally outside this service: it fetches Gmail data, resolves the sender
 * to a company, and supplies bytes here. This keeps Gmail SDK details out of the domain and makes the
 * import path testable without network access.</p>
 */
@Service
@Transactional
public class EmailAttachmentIngestionService {

    private static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024;

    private final EmailMessageRepository messages;
    private final EmailAttachmentRepository attachments;
    private final EmailConnectionRepository connections;
    private final RepresentativeLinkDirectory representatives;
    private final DocumentService documents;
    private final NotificationService notifications;
    private final AttachmentMalwareScanner malwareScanner;
    private final EmailClassificationRules classificationRules;

    public EmailAttachmentIngestionService(EmailMessageRepository messages,
                                           EmailAttachmentRepository attachments,
                                           EmailConnectionRepository connections,
                                           RepresentativeLinkDirectory representatives,
                                           DocumentService documents,
                                           NotificationService notifications) {
        this(messages, attachments, connections, representatives, documents, notifications,
                new ro.myfinance.ingestion.adapter.external.HeuristicAttachmentMalwareScanner(),
                new EmailClassificationRules());
    }

    @Autowired
    public EmailAttachmentIngestionService(EmailMessageRepository messages,
                                           EmailAttachmentRepository attachments,
                                           EmailConnectionRepository connections,
                                           RepresentativeLinkDirectory representatives,
                                           DocumentService documents,
                                           NotificationService notifications,
                                           AttachmentMalwareScanner malwareScanner,
                                           EmailClassificationRules classificationRules) {
        this.messages = messages;
        this.attachments = attachments;
        this.connections = connections;
        this.representatives = representatives;
        this.documents = documents;
        this.notifications = notifications;
        this.malwareScanner = malwareScanner;
        this.classificationRules = classificationRules;
    }

    /**
     * Resolves the sender to exactly one tenant-local representative/company link. Unknown or ambiguous
     * senders are recorded for review and never imported into a company.
     */
    public Result ingest(EmailConnection connection, IncomingMessage message, LocalDate periodMonth) {
        UUID tenantId = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to email ingestion"));
        EmailConnection ownedConnection = connections.findById(connection.getId())
                .filter(candidate -> tenantId.equals(candidate.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Email connection is not in the current tenant"));

        EmailMessage row = findOrCreateMessage(tenantId, ownedConnection, message);
        var links = representatives.findByRepresentativeEmail(message.senderEmail()).stream()
                .map(link -> link.getCompanyId())
                .distinct()
                .toList();
        if (links.size() != 1) {
            String reason = links.isEmpty()
                    ? "Sender is not linked to a representative in this tenant"
                    : "Sender is linked to multiple companies";
            boolean firstReview = row.getStatus() != EmailMessage.Status.NEEDS_REVIEW;
            Result result = recordSenderReview(row, message.attachments(), reason);
            if (firstReview) {
                notifications.emailSenderNeedsReview(message.senderEmail(), message.subject(), reason);
            }
            return result;
        }
        return ingestResolved(ownedConnection, row, message, links.get(0), periodMonth);
    }

    private Result ingestResolved(EmailConnection connection, EmailMessage row,
                                  IncomingMessage message, UUID companyId, LocalDate periodMonth) {
        int imported = 0;
        int duplicates = 0;
        int rejected = 0;
        int needsReview = 0;
        for (IncomingAttachment input : message.attachments()) {
            if (attachments.findByMessageIdAndGmailAttachmentId(row.getId(), input.gmailAttachmentId())
                    .isPresent()) {
                duplicates++;
                continue;
            }

            EmailAttachment attachment = new EmailAttachment(row.getTenantId(), row.getId(),
                    input.gmailAttachmentId(), input.filename(), input.contentType());
            byte[] bytes = input.bytes();
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_ATTACHMENT_BYTES) {
                attachment.setStatus(EmailAttachment.Status.REJECTED);
                attachment.setDetail("Attachment is empty or exceeds 20 MB");
                attachments.save(attachment);
                rejected++;
                continue;
            }
            EmailClassificationRules.Decision classification = classificationRules.classify(
                    message.senderEmail(), message.subject(), input.filename());
            if (classification.ambiguous()) {
                attachment.setStatus(EmailAttachment.Status.NEEDS_REVIEW);
                attachment.setDetail("Email subject and filename suggest multiple document types");
                attachments.save(attachment);
                needsReview++;
                continue;
            }
            AttachmentMalwareScanner.ScanResult scan = malwareScanner.scan(
                    input.filename(), input.contentType(), bytes);
            if (!scan.clean()) {
                attachment.setStatus(EmailAttachment.Status.REJECTED);
                attachment.setDetail(scan.detail());
                attachments.save(attachment);
                rejected++;
                continue;
            }

            String hash = ContentHash.sha256(bytes);
            attachment.setContentSha256(hash);
            if (attachments.existsByContentSha256AndStatus(hash, EmailAttachment.Status.IMPORTED.name())) {
                attachment.setStatus(EmailAttachment.Status.DUPLICATE);
                attachment.setDetail("Attachment content was already imported");
                attachments.save(attachment);
                duplicates++;
                continue;
            }

            try {
                Document document = documents.upload(companyId, periodMonth, input.filename(),
                        input.contentType(), bytes, classification.forcedType(), DocumentSource.EMAIL_AGENT);
                attachment.setDocumentId(document.getId());
                attachment.setStatus(EmailAttachment.Status.IMPORTED);
                attachments.save(attachment);
                notifications.emailDocumentImported(companyId, document.getId(), input.filename(), false);
                imported++;
            } catch (RuntimeException ex) {
                attachment.setStatus(EmailAttachment.Status.NEEDS_REVIEW);
                attachment.setDetail("Attachment requires review");
                attachments.save(attachment);
                notifications.emailDocumentImported(companyId, null, input.filename(), true);
                needsReview++;
            }
        }

        int reviewCount = rejected + needsReview;
        row.setStatus(reviewCount > 0 ? EmailMessage.Status.NEEDS_REVIEW : EmailMessage.Status.PROCESSED);
        row.setDetail("Imported %d, duplicate %d, rejected/review %d"
                .formatted(imported, duplicates, reviewCount));
        messages.save(row);
        return new Result(row.getId(), imported, duplicates, reviewCount);
    }

    private EmailMessage findOrCreateMessage(UUID tenantId, EmailConnection connection,
                                             IncomingMessage message) {
        return messages.findByConnectionIdAndGmailMessageId(connection.getId(), message.gmailMessageId())
                .orElseGet(() -> messages.save(new EmailMessage(tenantId, connection.getId(),
                        message.gmailMessageId(), message.threadId(), message.senderEmail(),
                        message.subject(), message.receivedAt())));
    }

    private Result recordSenderReview(EmailMessage row, List<IncomingAttachment> inputs, String reason) {
        int reviewCount = 0;
        for (IncomingAttachment input : inputs) {
            if (attachments.findByMessageIdAndGmailAttachmentId(row.getId(), input.gmailAttachmentId())
                    .isPresent()) {
                continue;
            }
            EmailAttachment attachment = new EmailAttachment(row.getTenantId(), row.getId(),
                    input.gmailAttachmentId(), input.filename(), input.contentType());
            if (input.bytes() != null && input.bytes().length > 0) {
                attachment.setContentSha256(ContentHash.sha256(input.bytes()));
            }
            attachment.setStatus(EmailAttachment.Status.NEEDS_REVIEW);
            attachment.setDetail(reason);
            attachments.save(attachment);
            reviewCount++;
        }
        row.setStatus(EmailMessage.Status.NEEDS_REVIEW);
        row.setDetail(reason);
        messages.save(row);
        return new Result(row.getId(), 0, 0, reviewCount == 0 ? 1 : reviewCount);
    }

    public record IncomingMessage(String gmailMessageId, String threadId, String senderEmail,
                                   String subject, Instant receivedAt, List<IncomingAttachment> attachments) {
        public IncomingMessage {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    public record IncomingAttachment(String gmailAttachmentId, String filename, String contentType,
                                     byte[] bytes) {
    }

    public record Result(UUID messageId, int imported, int duplicates, int needsReview) {
    }
}
