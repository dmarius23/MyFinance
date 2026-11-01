package ro.myfinance.common.whatsapp;

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
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.email.EmailStatus;
import ro.myfinance.common.email.UuidListConverter;

/** Dedicated tenant-scoped WhatsApp message history and provider lifecycle state. */
@Entity
@Table(name = "whatsapp_message")
public class WhatsAppMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmailKind kind;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmailStatus status;

    private String error;

    @Convert(converter = UuidListConverter.class)
    @Column(name = "related_ids", nullable = false)
    private List<UUID> relatedIds;

    @Convert(converter = UuidListConverter.class)
    @Column(name = "attachment_document_ids", nullable = false)
    private List<UUID> attachmentDocumentIds;

    @Column(name = "template_name")
    private String templateName;

    @Column(name = "template_language")
    private String templateLanguage;

    @Column(name = "template_variables")
    private String templateVariables;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    @Column(name = "sent_by")
    private UUID sentBy;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "idempotency_hash", nullable = false)
    private String idempotencyHash;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "provider_status")
    private String providerStatus;

    @Column(name = "provider_status_at")
    private Instant providerStatusAt;

    protected WhatsAppMessage() {
    }

    public WhatsAppMessage(UUID tenantId, EmailKind kind, UUID companyId, LocalDate periodMonth,
                           List<UUID> relatedIds, List<UUID> attachmentDocumentIds, String recipient,
                           String body, EmailStatus status, UUID sentBy, String idempotencyKey,
                           String idempotencyHash, String templateName, String templateLanguage,
                           String templateVariables) {
        this.tenantId = tenantId;
        this.kind = kind;
        this.companyId = companyId;
        this.periodMonth = periodMonth;
        this.relatedIds = relatedIds == null ? List.of() : relatedIds;
        this.attachmentDocumentIds = attachmentDocumentIds == null ? List.of() : attachmentDocumentIds;
        this.recipient = recipient;
        this.body = body;
        this.status = status;
        this.sentBy = sentBy;
        this.idempotencyKey = idempotencyKey;
        this.idempotencyHash = idempotencyHash;
        this.templateName = templateName;
        this.templateLanguage = templateLanguage;
        this.templateVariables = templateVariables;
    }

    public void markSent() {
        status = EmailStatus.SENT;
        error = null;
    }

    /** A failed attempt that will be retried: keep the row QUEUED but expose why the last attempt failed. */
    public void recordDeliveryError(String message) {
        error = message;
    }

    public void markFailed(String message) {
        status = EmailStatus.FAILED;
        error = message;
    }

    public void recordProviderMessage(String id, Instant at) {
        if (id != null && !id.isBlank()) {
            providerMessageId = id;
        }
        applyProviderStatus("accepted", at);
    }

    public void applyProviderStatus(String nextStatus, Instant at) {
        if (nextStatus == null || nextStatus.isBlank()) {
            return;
        }
        String normalized = nextStatus.toLowerCase(java.util.Locale.ROOT);
        if (providerStatus != null && rank(normalized) <= rank(providerStatus)) {
            return;
        }
        providerStatus = normalized;
        providerStatusAt = at == null ? Instant.now() : at;
        if ("failed".equals(normalized)) {
            markFailed("WhatsApp provider reported delivery failure");
        } else if ("sent".equals(normalized) || "delivered".equals(normalized)
                || "read".equals(normalized)) {
            markSent();
        }
    }

    private static int rank(String value) {
        return switch (value) {
            case "accepted" -> 1;
            case "sent", "failed" -> 2;
            case "delivered" -> 3;
            case "read" -> 4;
            default -> 0;
        };
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public EmailKind getKind() { return kind; }
    public UUID getCompanyId() { return companyId; }
    public LocalDate getPeriodMonth() { return periodMonth; }
    public String getRecipient() { return recipient; }
    public String getBody() { return body; }
    public EmailStatus getStatus() { return status; }
    public String getError() { return error; }
    public Instant getSentAt() { return sentAt; }
    public UUID getSentBy() { return sentBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getIdempotencyHash() { return idempotencyHash; }
    public String getProviderMessageId() { return providerMessageId; }
    public String getProviderStatus() { return providerStatus; }
    public Instant getProviderStatusAt() { return providerStatusAt; }
}
