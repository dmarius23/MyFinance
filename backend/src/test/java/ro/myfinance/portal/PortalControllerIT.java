package ro.myfinance.portal;

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
import ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.access.domain.RepresentativeLink;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.portal.application.PortalService;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Testcontainers coverage for the representative-facing {@code portal} BFF (S18). Portal resolves the
 * active company from the {@code X-Company-Id} header off a live request, so it is exercised end-to-end
 * through MockMvc with a real REPRESENTATIVE token. Covers the happy path ({@code /portal/me} returns the
 * rep's linked company) and the cross-company negative-authZ guard (a rep naming a company they are NOT
 * linked to is rejected) — the data-layer complement to {@code UrlAuthorizationBackstopIT}'s URL backstop.
 */
@AutoConfigureMockMvc
class PortalControllerIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("77777777-0000-0000-0000-000000000007");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RepresentativeLinkRepository repLinks;

    private UUID repId;
    private UUID linkedCompany;
    private UUID otherCompany;

    @BeforeEach
    void seed() {
        // Seed under a staff context so the RLS-bound inserts carry app.tenant_id = TENANT.
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing",
                TENANT);
        repId = seedUser("rep");
        linkedCompany = seedCompany("Acme SRL");
        otherCompany = seedCompany("Globex SA");
        repLinks.save(new RepresentativeLink(TENANT, repId, linkedCompany));
        TenantContext.clear();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private UUID seedUser(String label) {
        UUID id = UUID.randomUUID();
        String email = label + "+" + id + "@client.ro"; // unique per seed (@BeforeEach runs before every test)
        jdbc.update("insert into app_user(id, tenant_id, email, name, role, status) values (?,?,?,?, 'REPRESENTATIVE','ACTIVE')",
                id, TENANT, email, label);
        return id;
    }

    private UUID seedCompany(String legalName) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into company(id, tenant_id, legal_name, cui, status) values (?, ?, ?, ?, 'ACTIVE')",
                id, TENANT, legalName, "RO-" + id);
        return id;
    }

    private RequestPostProcessor repToken() {
        return jwt().jwt(j -> j.subject(repId.toString())
                        .claim("tenant_id", TENANT.toString())
                        .claim("role", "REPRESENTATIVE"))
                .authorities(new SimpleGrantedAuthority("ROLE_REPRESENTATIVE"));
    }

    @Test
    void repSeesTheirLinkedCompany() throws Exception {
        mvc.perform(get("/api/v1/portal/me").with(repToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(linkedCompany.toString()))
                .andExpect(jsonPath("$.name").value("Acme SRL"));
    }

    @Test
    void repCannotSelectACompanyTheyAreNotLinkedTo() throws Exception {
        mvc.perform(get("/api/v1/portal/me")
                        .header(PortalService.COMPANY_HEADER, otherCompany.toString())
                        .with(repToken()))
                .andExpect(status().isNotFound());
    }
}
