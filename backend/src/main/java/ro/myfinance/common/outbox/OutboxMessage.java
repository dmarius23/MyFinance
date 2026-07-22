package ro.myfinance.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Transactional-outbox row: written inside a business transaction so a dispatch that must not be lost on
 * crash (currently email delivery) commits atomically with the change that triggered it. The worker
 * {@link OutboxRelay} drains PENDING rows, invokes the handler for {@link #getType()}, and moves each to
 * SENT, or back to PENDING with a later {@code nextAttemptAt} (exponential backoff), or to DLQ after the
 * attempts cap. Redelivery is at-least-once, so handlers must be idempotent.
 */
@Entity
@Table(name = "outbox_message")
public class OutboxMessage {

    public enum Status { PENDING, PROCESSING, SENT, DLQ }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id")
    private String aggregateId;

    @Column(name = "type", nullable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "error")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    protected OutboxMessage() {
    }

    public OutboxMessage(UUID tenantId, String aggregateType, String aggregateId, String type, String payload) {
        this.tenantId = tenantId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.type = type;
        this.payload = payload;
    }

    /** Mark this row delivered (releasing the claim). */
    public void markSent(Instant now) {
        this.status = Status.SENT;
        this.sentAt = now;
        this.error = null;
        this.claimedAt = null;
    }

    /**
     * Record a failed attempt: release the claim and back off to {@code nextAttemptAt} (status PENDING), or
     * move to DLQ once {@code maxAttempts} is hit.
     */
    public void recordFailure(String message, Instant nextAttempt, int maxAttempts) {
        this.attempts += 1;
        this.error = message;
        this.status = this.attempts >= maxAttempts ? Status.DLQ : Status.PENDING;
        this.nextAttemptAt = nextAttempt;
        this.claimedAt = null;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getSentAt() { return sentAt; }
    public Instant getClaimedAt() { return claimedAt; }
}
