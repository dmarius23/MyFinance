package ro.myfinance.common.email;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One record of an email send, shared by every module (tax, reports, payroll, document reminders). The
 * {@link EmailKind kind} discriminator keeps each module's history separate. Append-only: a resend is a
 * new row, preserving the full history each module's notification log shows. Written through
 * {@code access.application.EmailDispatchService}.
 *
 * <p>{@code relatedIds} carries the ids a send referenced — the tax declarations covered (TAX) or the
 * documents attached (PAYROLL); empty for REPORT / DOCUMENT_REMINDER.
 */
@Entity
@Table(name = "email_history")
public class EmailHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private EmailKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false)
    private MessageChannel channel = MessageChannel.EMAIL;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    private String recipient;

    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EmailStatus status;

    private String error;

    @Convert(converter = UuidListConverter.class)
    @Column(name = "related_ids", nullable = false)
    private List<UUID> relatedIds;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    @Column(name = "sent_by")
    private UUID sentBy;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "idempotency_hash")
    private String idempotencyHash;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "provider_status")
    private String providerStatus;

    @Column(name = "provider_status_at")
    private Instant providerStatusAt;

    protected EmailHistory() {
    }

    public EmailHistory(UUID tenantId, EmailKind kind, UUID companyId, LocalDate periodMonth,
                        List<UUID> relatedIds, String recipient, String body, EmailStatus status,
                        String error, UUID sentBy) {
        this(tenantId, kind, MessageChannel.EMAIL, companyId, periodMonth, relatedIds, recipient, body,
                status, error, sentBy);
    }

    public EmailHistory(UUID tenantId, EmailKind kind, MessageChannel channel, UUID companyId,
                        LocalDate periodMonth, List<UUID> relatedIds, String recipient, String body,
                        EmailStatus status, String error, UUID sentBy) {
        this(tenantId, kind, channel, companyId, periodMonth, relatedIds, recipient, body, status, error,
                sentBy, null, null);
    }

    public EmailHistory(UUID tenantId, EmailKind kind, MessageChannel channel, UUID companyId,
                        LocalDate periodMonth, List<UUID> relatedIds, String recipient, String body,
                        EmailStatus status, String error, UUID sentBy, String idempotencyKey,
                        String idempotencyHash) {
        this.tenantId = tenantId;
        this.kind = kind;
        this.channel = channel;
        this.companyId = companyId;
        this.periodMonth = periodMonth;
        this.relatedIds = relatedIds == null ? List.of() : relatedIds;
        this.recipient = recipient;
        this.body = body;
        this.status = status;
        this.error = error;
        this.sentBy = sentBy;
        this.idempotencyKey = idempotencyKey;
        this.idempotencyHash = idempotencyHash;
    }

    /** Relay callback: the queued email was delivered. */
    public void markSent() {
        this.status = EmailStatus.SENT;
        this.error = null;
    }

    public void recordProviderMessage(String providerMessageId, Instant at) {
        if (providerMessageId != null && !providerMessageId.isBlank()) {
            this.providerMessageId = providerMessageId;
        }
        applyProviderStatus("accepted", at);
    }

    /**
     * Applies provider lifecycle events in monotonic order. Duplicate and late events are harmless.
     * A provider failure cannot move an already delivered/read message backwards.
     */
    public void applyProviderStatus(String nextStatus, Instant at) {
        if (nextStatus == null || nextStatus.isBlank()) {
            return;
        }
        String normalized = nextStatus.toLowerCase(java.util.Locale.ROOT);
        if (providerStatus != null && statusRank(normalized) <= statusRank(providerStatus)) {
            return;
        }
        providerStatus = normalized;
        providerStatusAt = at == null ? Instant.now() : at;
        if ("failed".equals(normalized)) {
            markFailed("WhatsApp provider reported delivery failure");
        } else if ("sent".equals(normalized) || "delivered".equals(normalized)
                || "read".equals(normalized)) {
            status = EmailStatus.SENT;
            error = null;
        }
    }

    private static int statusRank(String value) {
        return switch (value) {
            case "accepted" -> 1;
            case "sent" -> 2;
            case "delivered" -> 3;
            case "read" -> 4;
            case "failed" -> 2;
            default -> 0;
        };
    }

    /** Relay callback: delivery permanently failed (outbox message reached the DLQ). */
    public void markFailed(String errorMessage) {
        this.status = EmailStatus.FAILED;
        this.error = errorMessage;
    }

    public UUID getId() { return id; }
    public EmailKind getKind() { return kind; }
    public MessageChannel getChannel() { return channel; }
    public UUID getCompanyId() { return companyId; }
    public LocalDate getPeriodMonth() { return periodMonth; }
    public String getRecipient() { return recipient; }
    public String getBody() { return body; }
    public EmailStatus getStatus() { return status; }
    public String getError() { return error; }
    public List<UUID> getRelatedIds() { return relatedIds; }
    public Instant getSentAt() { return sentAt; }
    public UUID getSentBy() { return sentBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getIdempotencyHash() { return idempotencyHash; }
    public String getProviderMessageId() { return providerMessageId; }
    public String getProviderStatus() { return providerStatus; }
    public Instant getProviderStatusAt() { return providerStatusAt; }
}
