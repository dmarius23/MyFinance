package ro.myfinance.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.whatsapp.MetaWhatsAppOAuthStateRepository;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplate;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplateRepository;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Cross-tenant isolation (golden rule #1) for the data-access paths added for WhatsApp template hygiene:
 * disabling mappings by Meta template name, disabling every mapping on a WABA switch, and atomically
 * consuming a Meta OAuth state. Each must only ever touch the bound tenant's rows.
 */
class WhatsAppTemplateIsolationIT extends AbstractPostgresIT {

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @Autowired TenantWhatsAppTemplateRepository templates;
    @Autowired MetaWhatsAppOAuthStateRepository states;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void lookupByMetaTemplateNameSeesOnlyTheBoundTenantsMappings() {
        seedMapping(tenantA, "shared_name");
        seedMapping(tenantB, "shared_name");

        bind(tenantA);
        assertThat(templates.findByMetaTemplateName("shared_name"))
                .extracting(TenantWhatsAppTemplate::getTenantId).containsOnly(tenantA);
    }

    @Test
    void disablingAllMappingsOnAWabaSwitchLeavesOtherTenantsApproved() {
        seedMapping(tenantA, "tax_a");
        seedMapping(tenantB, "tax_b");

        bind(tenantA);
        tx.executeWithoutResult(s -> templates.findAll().forEach(TenantWhatsAppTemplate::disable));

        bind(tenantB);
        assertThat(templates.findByMetaTemplateName("tax_b"))
                .extracting(TenantWhatsAppTemplate::getStatus)
                .containsExactly(TenantWhatsAppTemplate.Status.APPROVED);
    }

    @Test
    void anotherTenantCannotConsumeAnOAuthState() {
        UUID stateId = UUID.randomUUID();
        bind(tenantA);
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 'A', 'ACTIVE', 'STD') on conflict do nothing",
                tenantA);
        jdbc.update("""
                insert into whatsapp_meta_oauth_state(id, tenant_id, user_id, state_hash, expires_at)
                values (?, ?, ?, ?, ?)
                """, stateId, tenantA, UUID.randomUUID(), "hash-" + stateId,
                java.sql.Timestamp.from(Instant.now().plusSeconds(600)));

        bind(tenantB);
        Integer stolen = tx.execute(s -> states.consumeIfUsable(stateId, Instant.now()));
        assertThat(stolen).isZero();

        bind(tenantA);
        Integer claimed = tx.execute(s -> states.consumeIfUsable(stateId, Instant.now()));
        Integer replayed = tx.execute(s -> states.consumeIfUsable(stateId, Instant.now()));
        assertThat(claimed).isEqualTo(1);
        assertThat(replayed).isZero(); // single use
    }

    private void seedMapping(UUID tenant, String name) {
        bind(tenant);
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing",
                tenant);
        jdbc.update("""
                insert into tenant_whatsapp_template(tenant_id, kind, source_kind, language, meta_template_name, status)
                values (?, 'TAX', 'TAX', 'ro_RO', ?, 'APPROVED')
                """, tenant, name);
    }

    private static void bind(UUID tenant) {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
    }
}
