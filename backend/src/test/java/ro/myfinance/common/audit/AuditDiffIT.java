package ro.myfinance.common.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * The before/after change snapshots round-trip through the {@code jsonb} columns and are PII-masked on the
 * way in. Verifies the full path: {@link AuditRecorder#record} → {@code audit_entry.before/after} → read back.
 */
class AuditDiffIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000fa");

    @Autowired AuditRecorder recorder;
    @Autowired AuditRepository audit;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void recordsMaskedBeforeAndAfterAsJson() {
        UUID actor = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(TENANT, actor, Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", TENANT);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", "OLD");
        before.put("email", "old.user@firma.ro");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "NEW");
        after.put("email", "new.user@firma.ro");

        recorder.record("WIDGET_CHANGED", "widget", UUID.randomUUID(), before, after);

        AuditEntry e = audit.findByActorIdOrderByAtDesc(actor).stream()
                .filter(x -> "WIDGET_CHANGED".equals(x.getAction())).findFirst().orElseThrow();
        assertThat(e.getBefore()).containsEntry("status", "OLD");
        assertThat(e.getBefore().get("email")).isEqualTo("o***@firma.ro"); // masked, not the raw address
        assertThat(e.getAfter()).containsEntry("status", "NEW");
        assertThat(e.getAfter().get("email")).isEqualTo("n***@firma.ro");
    }
}
