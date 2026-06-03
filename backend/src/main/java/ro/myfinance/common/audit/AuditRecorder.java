package ro.myfinance.common.audit;

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
        TenantContext.current().ifPresent(id -> repository.save(new AuditEntry(
                id.tenantId(), id.userId(), id.role() == null ? null : id.role().name(),
                action, entity, entityId)));
    }
}
