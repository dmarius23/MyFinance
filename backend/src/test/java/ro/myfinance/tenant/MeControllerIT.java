package ro.myfinance.tenant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * {@code /api/v1/me} reports {@code emailConfigured} = whether the tenant has a sendable SMTP provider, so
 * the UI can disable every email-send action until the firm sets one up. RLS-scoped to the caller's tenant.
 */
@AutoConfigureMockMvc
class MeControllerIT extends AbstractPostgresIT {

    private static final UUID TENANT_NO_EMAIL = UUID.fromString("cccccccc-0000-0000-0000-0000000000e1");
    private static final UUID TENANT_WITH_EMAIL = UUID.fromString("cccccccc-0000-0000-0000-0000000000e2");
    private static final UUID TENANT_WITH_WHATSAPP = UUID.fromString("cccccccc-0000-0000-0000-0000000000e3");
    private static final UUID TENANT_STAFF = UUID.fromString("cccccccc-0000-0000-0000-0000000000e4");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private void seedTenant(UUID tenant) {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", tenant);
        } finally {
            TenantContext.clear();
        }
    }

    private void seedSendableEmailProvider(UUID tenant) {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            jdbc.update("""
                    insert into tenant_email_provider(tenant_id, enabled, from_email, smtp_host, smtp_port, updated_at)
                    values (?, true, 'contact@firma.ro', 'smtp.firma.ro', 587, now())
                    on conflict (tenant_id) do update set enabled = excluded.enabled,
                        from_email = excluded.from_email, smtp_host = excluded.smtp_host""", tenant);
        } finally {
            TenantContext.clear();
        }
    }

    private static RequestPostProcessor admin(UUID tenant) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", tenant.toString()).claim("role", "TENANT_ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"));
    }

    private void seedTwilioWhatsAppProvider(UUID tenant) {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            jdbc.update("""
                    insert into tenant_whatsapp_provider(tenant_id, mode, account_sid, auth_token_enc, from_number, updated_at)
                    values (?, 'TWILIO', 'AC123', 'enc', '+14155238886', now())
                    on conflict (tenant_id) do update set mode = excluded.mode,
                        account_sid = excluded.account_sid, auth_token_enc = excluded.auth_token_enc,
                        from_number = excluded.from_number""", tenant);
        } finally {
            TenantContext.clear();
        }
    }

    private UUID seedStaff(UUID tenant, String status) {
        UUID id = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            jdbc.update("insert into app_user(id, tenant_id, email, name, role, status)"
                            + " values (?, ?, ?, 'Ana', 'EMPLOYEE', ?)",
                    id, tenant, "staff-" + id + "@firma.ro", status);
        } finally {
            TenantContext.clear();
        }
        return id;
    }

    private String statusOf(UUID tenant, UUID userId) {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            return jdbc.queryForObject("select status from app_user where id = ?", String.class, userId);
        } finally {
            TenantContext.clear();
        }
    }

    private Object lastLoginOf(UUID tenant, UUID userId) {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            return jdbc.queryForObject("select last_login from app_user where id = ?", Object.class, userId);
        } finally {
            TenantContext.clear();
        }
    }

    /** The staff shell's bootstrap call — JWT subject is the app_user id. */
    private static RequestPostProcessor staff(UUID tenant, UUID userId) {
        return jwt().jwt(j -> j.subject(userId.toString())
                        .claim("tenant_id", tenant.toString()).claim("role", "EMPLOYEE"))
                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"));
    }

    /**
     * The regression this guards: {@code SignInRecorder} was wired ONLY into {@code PortalService.me()} —
     * the representative portal — so firm staff (TENANT_ADMIN / EMPLOYEE) were never promoted off
     * {@code INVITED} no matter how long they had been using the app, and the Team page misreported who
     * had actually accepted their invitation. The staff shell bootstraps on {@code GET /api/v1/me}.
     */
    @Test
    void promotesAnInvitedStaffUserToActiveOnBootstrap() throws Exception {
        seedTenant(TENANT_STAFF);
        UUID userId = seedStaff(TENANT_STAFF, "INVITED");

        mvc.perform(get("/api/v1/me").with(staff(TENANT_STAFF, userId)))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(statusOf(TENANT_STAFF, userId)).isEqualTo("ACTIVE");
        org.assertj.core.api.Assertions.assertThat(lastLoginOf(TENANT_STAFF, userId)).isNotNull();
    }

    /** Same for a staff member who was bulk-provisioned and never emailed. */
    @Test
    void promotesAPendingInviteStaffUserToActiveOnBootstrap() throws Exception {
        seedTenant(TENANT_STAFF);
        UUID userId = seedStaff(TENANT_STAFF, "PENDING_INVITE");

        mvc.perform(get("/api/v1/me").with(staff(TENANT_STAFF, userId)))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(statusOf(TENANT_STAFF, userId)).isEqualTo("ACTIVE");
    }

    /** A deactivated account must never resurrect itself just by presenting a still-valid token. */
    @Test
    void leavesADeactivatedStaffUserInactive() throws Exception {
        seedTenant(TENANT_STAFF);
        UUID userId = seedStaff(TENANT_STAFF, "INACTIVE");

        mvc.perform(get("/api/v1/me").with(staff(TENANT_STAFF, userId)))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(statusOf(TENANT_STAFF, userId)).isEqualTo("INACTIVE");
    }

    @Test
    void emailNotConfiguredWhenNoProvider() throws Exception {
        seedTenant(TENANT_NO_EMAIL);
        mvc.perform(get("/api/v1/me").with(admin(TENANT_NO_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfigured").value(false))
                .andExpect(jsonPath("$.whatsappConfigured").value(false));
    }

    @Test
    void emailConfiguredWhenSendableProviderExists() throws Exception {
        seedTenant(TENANT_WITH_EMAIL);
        seedSendableEmailProvider(TENANT_WITH_EMAIL);
        mvc.perform(get("/api/v1/me").with(admin(TENANT_WITH_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfigured").value(true));
    }

    @Test
    void whatsappConfiguredWhenTwilioProviderComplete() throws Exception {
        seedTenant(TENANT_WITH_WHATSAPP);
        seedTwilioWhatsAppProvider(TENANT_WITH_WHATSAPP);
        mvc.perform(get("/api/v1/me").with(admin(TENANT_WITH_WHATSAPP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.whatsappConfigured").value(true));
    }
}
