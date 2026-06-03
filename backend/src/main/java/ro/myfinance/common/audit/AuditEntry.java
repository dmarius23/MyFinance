package ro.myfinance.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

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

    protected AuditEntry() {
    }

    public AuditEntry(UUID tenantId, UUID actorId, String actorRole, String action, String entity, UUID entityId) {
        this.tenantId = tenantId;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.action = action;
        this.entity = entity;
        this.entityId = entityId;
    }

    public UUID getId() {
        return id;
    }
}
