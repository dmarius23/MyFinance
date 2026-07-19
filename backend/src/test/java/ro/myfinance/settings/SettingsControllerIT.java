package ro.myfinance.settings;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * The tenant Settings page exposes the global tax rates + treasury accounts read-only (resolved for
 * today, seeded by V35) and lets the tenant edit only its sender email.
 */
@AutoConfigureMockMvc
class SettingsControllerIT extends AbstractPostgresIT {

    // A dedicated tenant per test — these ITs commit and don't roll back, so a shared tenant would
    // let the sender-email mutation in one test bleed into another's assertions.
    private static final UUID TENANT_GET = UUID.fromString("dddddddd-0000-0000-0000-0000000000f1");
    private static final UUID TENANT_PUT = UUID.fromString("dddddddd-0000-0000-0000-0000000000f2");
    private static final UUID TENANT_TREASURY = UUID.fromString("dddddddd-0000-0000-0000-0000000000f3");

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

    private static RequestPostProcessor tenantAdmin(UUID tenant) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", tenant.toString()).claim("role", "TENANT_ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"));
    }

    @Test
    void getReturnsGlobalRatesReadOnlyAndSenderEmail() throws Exception {
        seedTenant(TENANT_GET);
        mvc.perform(get("/api/v1/settings").with(tenantAdmin(TENANT_GET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vatRate").value(21.00))     // seeded globally by V35
                .andExpect(jsonPath("$.microRate").value(3.00))
                .andExpect(jsonPath("$.profitRate").value(16.00))
                .andExpect(jsonPath("$.senderEmail").doesNotExist());
    }

    @Test
    void putUpdatesOnlyTheSenderEmail() throws Exception {
        seedTenant(TENANT_PUT);
        mvc.perform(put("/api/v1/settings")
                        .with(tenantAdmin(TENANT_PUT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senderEmail\":\"contact@firma.ro\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderEmail").value("contact@firma.ro"))
                .andExpect(jsonPath("$.vatRate").value(21.00)); // rates untouched, still from global
    }

    @Test
    void treasuryListIsReadableByTheTenant() throws Exception {
        seedTenant(TENANT_TREASURY);
        mvc.perform(get("/api/v1/settings/treasury-accounts").with(tenantAdmin(TENANT_TREASURY)))
                .andExpect(status().isOk());
    }
}
