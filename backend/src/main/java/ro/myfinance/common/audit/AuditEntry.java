package ro.myfinance.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** MOD-12 (minimal) — append-only audit record. Tenant-scoped by RLS. */
@Entity
@Table(name = "audit_entry")
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_role")
    private String actorRole;

    @Column(nullable = false)
    private String action;

    private String entity;

    @Column(name = "entity_id")
    private UUID entityId;

    @CreationTimestamp
    @Column(name = "at", nullable = false, updatable = false)
    private Instant at;

    /** State before/after the change (PII-masked JSON); null for creates/deletes or unaudited diffs. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before")
    private Map<String, Object> before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after")
    private Map<String, Object> after;

    protected AuditEntry() {
    }

    public AuditEntry(UUID tenantId, UUID actorId, String actorRole, String action, String entity, UUID entityId) {
        this(tenantId, actorId, actorRole, action, entity, entityId, null, null);
    }

    public AuditEntry(UUID tenantId, UUID actorId, String actorRole, String action, String entity, UUID entityId,
                      Map<String, Object> before, Map<String, Object> after) {
        this.tenantId = tenantId;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.action = action;
        this.entity = entity;
        this.entityId = entityId;
        this.before = before;
        this.after = after;
    }

    public UUID getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public String getEntity() {
        return entity;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public Instant getAt() {
        return at;
    }

    public Map<String, Object> getBefore() {
        return before;
    }

    public Map<String, Object> getAfter() {
        return after;
    }
}
