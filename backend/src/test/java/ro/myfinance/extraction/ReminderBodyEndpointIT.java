package ro.myfinance.extraction;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Smoke test for GET /api/v1/companies/{companyId}/document-reminders/body.
 *
 * <p>No bank statement is seeded, so the endpoint always returns REMINDER_NOTHING_RECEIVED.
 * The representative authZ guard is tested against a company that does not belong to the rep —
 * the controller's class-level @PreAuthorize blocks the request before the service is reached.
 */
@AutoConfigureMockMvc
class ReminderBodyEndpointIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000007");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private UUID companyId;
    private UUID adminUserId;

    @BeforeEach
    void seed() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 'Firma Test', 'ACTIVE', 'STD') on conflict do nothing", TENANT);
        adminUserId = UUID.randomUUID();
        jdbc.update("insert into app_user(id, tenant_id, email, name, role, status) values (?,?,?,?,'TENANT_ADMIN','ACTIVE')",
                adminUserId, TENANT, "admin+" + adminUserId + "@firma.ro", "Admin Test");
        companyId = UUID.randomUUID();
        jdbc.update("insert into company(id, tenant_id, legal_name, cui, status) values (?,?,?,?,'ACTIVE')",
                companyId, TENANT, "Acme SRL", "RO-" + companyId);
        TenantContext.clear();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void returnsStateSubjectAndBodyForTheCompanyAndPeriod() throws Exception {
        mvc.perform(get("/api/v1/companies/{id}/document-reminders/body", companyId)
                        .param("period", "2026-04-01")
                        .with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REMINDER_NOTHING_RECEIVED"))
                .andExpect(jsonPath("$.subject").value("Documente lipsă — Aprilie 2026"))
                .andExpect(jsonPath("$.body").value(containsString("Bună ziua,")));
    }

    @Test
    void representativesCannotReachIt() throws Exception {
        mvc.perform(get("/api/v1/companies/{id}/document-reminders/body", companyId)
                        .param("period", "2026-04-01")
                        .with(repAuth()))
                .andExpect(status().isForbidden());
    }

    private RequestPostProcessor adminAuth() {
        return jwt().jwt(j -> j.subject(adminUserId.toString())
                        .claim("tenant_id", TENANT.toString())
                        .claim("role", "TENANT_ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"));
    }

    private RequestPostProcessor repAuth() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT.toString())
                        .claim("role", "REPRESENTATIVE"))
                .authorities(new SimpleGrantedAuthority("ROLE_REPRESENTATIVE"));
    }
}
