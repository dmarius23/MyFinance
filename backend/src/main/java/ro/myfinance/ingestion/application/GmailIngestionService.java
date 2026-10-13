package ro.myfinance.ingestion.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.ingestion.adapter.persistence.EmailConnectionRepository;
import ro.myfinance.ingestion.adapter.persistence.EmailSyncClaimRepository;
import ro.myfinance.ingestion.domain.EmailConnection;

/** Coordinates Gmail history changes with the existing attachment/document intake pipeline. */
@Service
public class GmailIngestionService {

    private final EmailConnectionRepository connections;
    private final EmailSyncClaimRepository claims;
    private final GmailMailboxClient gmail;
    private final EmailAttachmentIngestionService attachments;
    private final MeterRegistry metrics;

    public GmailIngestionService(EmailConnectionRepository connections, EmailSyncClaimRepository claims,
                                 GmailMailboxClient gmail,
                                 EmailAttachmentIngestionService attachments) {
        this(connections, claims, gmail, attachments, null);
    }

    @Autowired
    public GmailIngestionService(EmailConnectionRepository connections, EmailSyncClaimRepository claims,
                                 GmailMailboxClient gmail,
                                 EmailAttachmentIngestionService attachments,
                                 MeterRegistry metrics) {
        this.connections = connections;
        this.claims = claims;
        this.gmail = gmail;
        this.attachments = attachments;
        this.metrics = metrics;
    }

    /**
     * Synchronizes changes after the supplied Gmail history id. The caller must acknowledge the Pub/Sub
     * notification only after this method succeeds; the cursor is advanced last.
     */
    public SyncResult sync(UUID connectionId, String notificationHistoryId) {
        EmailConnection connection = ownedConnection(connectionId);
        acquire(connection);
        try {
            try {
                SyncResult result = syncClaimed(connection, notificationHistoryId);
                count("sync.success");
                return result;
            } catch (GmailAuthorizationRevokedException e) {
                count("sync.unauthorized");
                markFailure(connection, "UNAUTHORIZED", "Gmail authorization revoked; reconnect required");
                return new SyncResult(0, 0, 1, false);
            } catch (GmailTransientException e) {
                count("sync.transient_failure");
                markFailure(connection, "FAILED", "Temporary Gmail error; retry scheduled");
                throw e;
            }
        } finally {
            claims.release(connection.getId());
        }
    }

    private SyncResult syncClaimed(EmailConnection connection, String notificationHistoryId) {
        connection.setStatus("SYNCING");
        connections.save(connection);
        String startHistoryId = connection.getGmailHistoryId();
        if (startHistoryId == null || startHistoryId.isBlank()) {
            var registration = gmail.watch(connection);
            connection.setGmailHistoryId(registration.historyId());
            connection.setWatchExpiresAt(registration.expiresAt());
            connections.save(connection);
            startHistoryId = registration.historyId();
        }

        List<GmailMailboxClient.MessageRef> changes;
        try {
            changes = gmail.changes(connection, startHistoryId);
        } catch (GmailHistoryExpiredException e) {
            connection.setStatus("NEEDS_REVIEW");
            connection.setLastResult("Gmail history expired; date backfill required");
            connections.save(connection);
            return new SyncResult(0, 0, 1, true);
        }
        Totals totals = importMessages(connection, changes);

        String nextHistoryId = notificationHistoryId == null || notificationHistoryId.isBlank()
                ? gmail.currentHistoryId(connection) : notificationHistoryId;
        if (nextHistoryId != null && !nextHistoryId.isBlank()) {
            connection.setGmailHistoryId(nextHistoryId);
        }
        connection.setLastSyncedAt(java.time.Instant.now());
        connection.setStatus("ACTIVE");
        connection.setLastResult("Processed %d message(s), %d imported, %d review"
                .formatted(totals.messages(), totals.imported(), totals.needsReview()));
        connections.save(connection);
        return new SyncResult(totals.messages(), totals.imported(), totals.needsReview(), false);
    }

    /** Explicitly recovers an expired cursor by backfilling a bounded date interval. */
    public SyncResult backfill(UUID connectionId, LocalDate fromInclusive, LocalDate toExclusive) {
        EmailConnection connection = ownedConnection(connectionId);
        acquire(connection);
        try {
            try {
                return backfillClaimed(connection, fromInclusive, toExclusive);
            } catch (GmailAuthorizationRevokedException e) {
                markFailure(connection, "UNAUTHORIZED", "Gmail authorization revoked; reconnect required");
                return new SyncResult(0, 0, 1, false);
            } catch (GmailTransientException e) {
                markFailure(connection, "FAILED", "Temporary Gmail error; retry scheduled");
                throw e;
            }
        } finally {
            claims.release(connection.getId());
        }
    }

    private void markFailure(EmailConnection connection, String status, String detail) {
        connection.setStatus(status);
        connection.setLastResult(detail);
        connections.save(connection);
    }

    private void count(String outcome) {
        if (metrics != null) {
            metrics.counter("myfinance.gmail.sync", "outcome", outcome).increment();
        }
    }

    private SyncResult backfillClaimed(EmailConnection connection, LocalDate fromInclusive,
                                       LocalDate toExclusive) {
        if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("Backfill interval must be non-empty");
        }
        var registration = gmail.watch(connection);
        connection.setGmailHistoryId(registration.historyId());
        connection.setWatchExpiresAt(registration.expiresAt());
        connection.setStatus("BACKFILLING");
        connections.save(connection);
        try {
            Totals totals = importMessages(connection, gmail.backfill(connection, fromInclusive, toExclusive));
            // Drain changes that arrived while the date-based backfill was running before advancing.
            totals = totals.add(importMessages(connection, gmail.changes(connection,
                    registration.historyId())));
            String currentHistoryId = gmail.currentHistoryId(connection);
            connection.setGmailHistoryId(currentHistoryId);
            connection.setStatus("ACTIVE");
            connection.setLastSyncedAt(java.time.Instant.now());
            connection.setLastResult("Backfilled %s to %s: %d message(s), %d imported, %d review"
                    .formatted(fromInclusive, toExclusive, totals.messages(), totals.imported(),
                            totals.needsReview()));
            connections.save(connection);
            return new SyncResult(totals.messages(), totals.imported(), totals.needsReview(), false);
        } catch (GmailHistoryExpiredException e) {
            connection.setStatus("NEEDS_REVIEW");
            connection.setLastResult("Gmail history expired during backfill");
            connections.save(connection);
            return new SyncResult(0, 0, 1, true);
        }
    }

    private void acquire(EmailConnection connection) {
        if (!claims.tryClaim(connection.getId(), connection.getTenantId(),
                java.time.Duration.ofMinutes(10))) {
            throw new GmailSyncAlreadyRunningException(
                    "Gmail sync already running for connection " + connection.getId());
        }
    }

    private Totals importMessages(EmailConnection connection,
                                  List<GmailMailboxClient.MessageRef> messages) {
        int imported = 0;
        int needsReview = 0;
        for (var message : messages) {
            List<EmailAttachmentIngestionService.IncomingAttachment> incoming = new ArrayList<>();
            for (var attachment : message.attachments()) {
                incoming.add(new EmailAttachmentIngestionService.IncomingAttachment(
                        attachment.gmailAttachmentId(), attachment.filename(), attachment.contentType(),
                        gmail.attachment(connection, message, attachment)));
            }
            var result = attachments.ingest(connection,
                    new EmailAttachmentIngestionService.IncomingMessage(message.gmailMessageId(),
                            message.threadId(), message.senderEmail(), message.subject(),
                            message.receivedAt(), incoming),
                    period(message.receivedAt()));
            imported += result.imported();
            needsReview += result.needsReview();
        }
        return new Totals(messages.size(), imported, needsReview);
    }

    private EmailConnection ownedConnection(UUID id) {
        UUID tenantId = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));
        return connections.findById(id)
                .filter(connection -> tenantId.equals(connection.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Email connection not found"));
    }

    private static LocalDate period(java.time.Instant receivedAt) {
        return receivedAt == null
                ? YearMonth.now(java.time.ZoneOffset.UTC).atDay(1)
                : receivedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
    }

    private record Totals(int messages, int imported, int needsReview) {
        Totals add(Totals other) {
            return new Totals(messages + other.messages, imported + other.imported,
                    needsReview + other.needsReview);
        }
    }

    public record SyncResult(int messages, int imported, int needsReview, boolean backfillRequired) {
    }
}
