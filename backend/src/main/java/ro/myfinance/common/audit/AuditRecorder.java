package ro.myfinance.common.audit;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ro.myfinance.common.security.TenantContext;

/** Writes audit entries using the current tenant identity. No-ops if no tenant is bound. */
@Component
public class AuditRecorder {

    private final AuditRepository repository;

    public AuditRecorder(AuditRepository repository) {
        this.repository = repository;
    }

    public void record(String action, String entity, UUID entityId) {
        record(action, entity, entityId, null, null);
    }

    /**
     * Record a change with its before/after state. {@code before}/{@code after} are field→value maps
     * (only the fields that matter); personal/sensitive values are PII-masked before they are stored.
     */
    public void record(String action, String entity, UUID entityId,
                       Map<String, Object> before, Map<String, Object> after) {
        TenantContext.current().ifPresent(id -> repository.save(new AuditEntry(
                id.tenantId(), id.userId(), id.role() == null ? null : id.role().name(),
                action, entity, entityId, AuditMasking.mask(before), AuditMasking.mask(after))));
    }
}
