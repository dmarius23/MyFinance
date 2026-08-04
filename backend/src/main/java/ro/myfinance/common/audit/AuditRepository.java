package ro.myfinance.common.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEntry, UUID> {

    /** All audit entries where the given user is the actor (tenant-scoped by RLS) — for GDPR export. */
    List<AuditEntry> findByActorIdOrderByAtDesc(UUID actorId);
}
