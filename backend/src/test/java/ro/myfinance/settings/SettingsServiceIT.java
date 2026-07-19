package ro.myfinance.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.settings.application.SettingsService;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Tenant-level settings now hold only the outbound sender email (tax rates + treasury moved to the
 * global reference tables in V35/V36). general_settings is a tenant table with RLS, so the mandatory
 * cross-tenant isolation test lives here.
 */
class SettingsServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000011");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000022");

    @Autowired SettingsService service;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void asTenant(UUID tenantId) {
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STANDARD') on conflict do nothing",
                tenantId, "T-" + tenantId);
    }

    @Test
    void senderEmailIsNullUntilConfigured() {
        asTenant(TENANT_A);
        assertThat(service.getSettings().getSenderEmail()).isNull();
        assertThat(service.senderEmail()).isNull();
    }

    @Test
    void getSettingsIsIdempotent() {
        asTenant(TENANT_A);
        service.getSettings();
        service.getSettings();
        assertThat(service.getSettings().getSenderEmail()).isNull();
    }

    @Test
    void updatesAndClearsSenderEmail() {
        asTenant(TENANT_A);
        service.updateSenderEmail("  contact@firma.ro  ");
        assertThat(service.senderEmail()).isEqualTo("contact@firma.ro"); // trimmed

        service.updateSenderEmail("   ");
        assertThat(service.senderEmail()).isNull(); // blank clears
    }

    @Test
    void tenantBCannotSeeTenantASenderEmail() {
        asTenant(TENANT_A);
        service.updateSenderEmail("a@firma.ro");

        asTenant(TENANT_B);
        assertThat(service.senderEmail()).isNull();
        assertThat(service.getSettings().getSenderEmail()).isNull();
    }
}
