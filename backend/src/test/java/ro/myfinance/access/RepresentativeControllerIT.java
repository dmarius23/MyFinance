package ro.myfinance.access;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.support.AbstractPostgresIT;

@AutoConfigureMockMvc
class RepresentativeControllerIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("dddddddd-0000-0000-0000-0000000000d1");
    private static final UUID OTHER_TENANT = UUID.fromString("eeeeeeee-0000-0000-0000-0000000000e1");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    /** Seeds a tenant + company via TenantContext (so RLS passes), then clears it so the MockMvc
     *  request can bind its own identity from the JWT. */
    private UUID seedCompany() {
        seedTenantRow(TENANT);
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            UUID companyId = UUID.randomUUID();
            jdbc.update("insert into company(id, tenant_id, legal_name, cui, status) values (?, ?, 'C', ?, 'ACTIVE')",
                    companyId, TENANT, "RO-" + companyId);
            return companyId;
        } finally {
            TenantContext.clear();
        }
    }

    private void seedTenantRow(UUID tenantId) {
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", tenantId);
        } finally {
            TenantContext.clear();
        }
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT.toString())
                        .claim("role", "TENANT_ADMIN"))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TENANT_ADMIN"));
    }

    @Test
    void invitesAndListsRepresentatives() throws Exception {
        UUID companyId = seedCompany();

        mvc.perform(post("/api/v1/companies/{id}/representatives", companyId)
                        .with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"rep@client.ro\",\"name\":\"Rep One\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("rep@client.ro"))
                .andExpect(jsonPath("$.status").value("INVITED"));

        mvc.perform(get("/api/v1/companies/{id}/representatives", companyId).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("rep@client.ro"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/companies/{id}/representatives", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cannotListRepresentativesOfAnotherTenantsCompany() throws Exception {
        UUID companyId = seedCompany(); // belongs to TENANT

        var otherTenantJwt = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", OTHER_TENANT.toString())
                        .claim("role", "TENANT_ADMIN"))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TENANT_ADMIN"));

        seedTenantRow(OTHER_TENANT);

        mvc.perform(get("/api/v1/companies/{id}/representatives", companyId).with(otherTenantJwt))
                .andExpect(status().isNotFound()); // RLS hides the company → NotFound
    }
}
