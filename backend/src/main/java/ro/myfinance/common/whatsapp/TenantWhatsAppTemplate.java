package ro.myfinance.common.whatsapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import ro.myfinance.common.email.EmailKind;

/** Tenant-owned mapping from a MyFinance message category to a Meta-approved template. */
@Entity
@Table(name = "tenant_whatsapp_template")
public class TenantWhatsAppTemplate {

    public enum Status { PENDING, APPROVED, DISABLED }
    public enum Category { UTILITY, MARKETING, AUTHENTICATION }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmailKind kind;

    @Column(nullable = false)
    private String language;

    @Column(name = "meta_template_name", nullable = false)
    private String metaTemplateName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category = Category.UTILITY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    private String version;

    @Column(name = "variable_schema")
    private String variableSchema;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TenantWhatsAppTemplate() {
    }

    public TenantWhatsAppTemplate(UUID tenantId, EmailKind kind, String language,
                                  String metaTemplateName, Category category, String version,
                                  String variableSchema) {
        this.tenantId = tenantId;
        this.kind = kind;
        this.language = language;
        this.metaTemplateName = metaTemplateName;
        this.category = category == null ? Category.UTILITY : category;
        this.version = version;
        this.variableSchema = variableSchema;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public EmailKind getKind() { return kind; }
    public String getLanguage() { return language; }
    public String getMetaTemplateName() { return metaTemplateName; }
    public Category getCategory() { return category; }
    public Status getStatus() { return status; }
    public String getVersion() { return version; }
    public String getVariableSchema() { return variableSchema; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(String metaTemplateName, Category category, String version,
                       String variableSchema, Status status) {
        this.metaTemplateName = metaTemplateName;
        this.category = category == null ? Category.UTILITY : category;
        this.version = version;
        this.variableSchema = variableSchema;
        this.status = status == null ? Status.PENDING : status;
        this.approvedAt = this.status == Status.APPROVED ? Instant.now() : null;
    }
}
