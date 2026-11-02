package ro.myfinance.common.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One tenant's wording for one message kind. Present only when customized — {@link MessageTemplateService}
 * falls back to {@link DefaultMessageTemplates} otherwise. RLS enforces tenant isolation.
 */
@Entity
@Table(name = "tenant_message_template")
@IdClass(MessageTemplate.Key.class)
public class MessageTemplate {

    /** Composite PK (tenant_id, kind). */
    public static class Key implements Serializable {
        private UUID tenantId;
        private MessageTemplateKind kind;

        public Key() {
        }

        public Key(UUID tenantId, MessageTemplateKind kind) {
            this.tenantId = tenantId;
            this.kind = kind;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key k)) {
                return false;
            }
            return Objects.equals(tenantId, k.tenantId) && kind == k.kind;
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, kind);
        }
    }

    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private MessageTemplateKind kind;

    /** Null means "use the built-in default subject" — a tenant may reword the body only. */
    @Column(name = "subject")
    private String subject;

    @Column(name = "body", nullable = false)
    private String body;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected MessageTemplate() {
    }

    public MessageTemplate(UUID tenantId, MessageTemplateKind kind) {
        this.tenantId = tenantId;
        this.kind = kind;
    }

    public UUID getTenantId() { return tenantId; }
    public MessageTemplateKind getKind() { return kind; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
