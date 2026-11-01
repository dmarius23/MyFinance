package ro.myfinance.settings;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Authorization and validation for the per-tenant message template endpoints.
 *
 * <p>Only {@code TENANT_ADMIN} may reach any endpoint — EMPLOYEE and REPRESENTATIVE must get 403.
 * Save-time validation (unknown placeholder, block in subject, bad delimiters) maps to HTTP 400
 * so the admin catches a typo in the editor rather than a client receiving literal {{compnayName}}.
 *
 * <p>A dedicated tenant UUID per stateful test avoids cross-test bleed: the ITs commit and do not
 * roll back, so a shared tenant would let one test's save/reset mutation affect another's assertions.
 */
@AutoConfigureMockMvc
class TemplateSettingsAuthzIT extends AbstractPostgresIT {

    // Unique tenant UUIDs for tests that mutate state
    private static final UUID TENANT_LIST  = UUID.fromString("cccccccc-0000-0000-0000-0000000000c1");
    private static final UUID TENANT_AUTHZ = UUID.fromString("cccccccc-0000-0000-0000-0000000000c2");
    private static final UUID TENANT_SAVE  = UUID.fromString("cccccccc-0000-0000-0000-0000000000c3");
    private static final UUID TENANT_VALID = UUID.fromString("cccccccc-0000-0000-0000-0000000000c4");
    private static final UUID TENANT_KIND  = UUID.fromString("cccccccc-0000-0000-0000-0000000000c5");
    private static final UUID TENANT_PREV  = UUID.fromString("cccccccc-0000-0000-0000-0000000000c6");

    @DynamicPropertySource
    static void secretKey(DynamicPropertyRegistry registry) {
        // A deterministic 32-byte AES key for the test (all-zero bytes, base64-encoded).
        registry.add("myfinance.secret.key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearTenantContext() {
        // The TenantContextFilter clears the ThreadLocal after each request, but seedTenant() binds
        // it outside the filter pipeline — always clear here to prevent leaks between tests.
        TenantContext.clear();
    }

    private void seedTenant(UUID tenant) {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            jdbc.update(
                    "insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing",
                    tenant);
        } finally {
            TenantContext.clear();
        }
    }

    // --- role helpers ---

    private static RequestPostProcessor adminAuth(UUID tenant) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", tenant.toString())
                        .claim("role", "TENANT_ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"));
    }

    private static RequestPostProcessor employeeAuth() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT_AUTHZ.toString())
                        .claim("role", "EMPLOYEE"))
                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"));
    }

    private static RequestPostProcessor repAuth() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT_AUTHZ.toString())
                        .claim("role", "REPRESENTATIVE"))
                .authorities(new SimpleGrantedAuthority("ROLE_REPRESENTATIVE"));
    }

    // --- tests ---

    @Test
    void tenantAdminCanListEveryKind() throws Exception {
        seedTenant(TENANT_LIST);
        mvc.perform(get("/api/v1/settings/messaging/templates").with(adminAuth(TENANT_LIST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[?(@.kind == 'TAX')].customized")
                        .value(org.hamcrest.Matchers.hasItem(false)));
    }

    @Test
    void employeeCannotRead() throws Exception {
        mvc.perform(get("/api/v1/settings/messaging/templates").with(employeeAuth()))
                .andExpect(status().isForbidden());
    }

    @Test
    void representativeCannotRead() throws Exception {
        mvc.perform(get("/api/v1/settings/messaging/templates").with(repAuth()))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCannotWrite() throws Exception {
        mvc.perform(put("/api/v1/settings/messaging/templates/{kind}", "PAYROLL")
                        .with(employeeAuth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"X\",\"body\":\"Bună ziua\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCannotReset() throws Exception {
        mvc.perform(delete("/api/v1/settings/messaging/templates/{kind}", "PAYROLL")
                        .with(employeeAuth()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownPlaceholderIsRejectedWithFourHundred() throws Exception {
        seedTenant(TENANT_VALID);
        mvc.perform(put("/api/v1/settings/messaging/templates/{kind}", "PAYROLL")
                        .with(adminAuth(TENANT_VALID)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"X\",\"body\":\"Bună ziua {{compnayName}}\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownKindIsRejectedWithFourHundred() throws Exception {
        seedTenant(TENANT_KIND);
        mvc.perform(get("/api/v1/settings/messaging/templates").with(adminAuth(TENANT_KIND)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/settings/messaging/templates/{kind}", "NOT_A_KIND")
                        .with(adminAuth(TENANT_KIND)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void saveThenResetRoundTripsBackToTheDefault() throws Exception {
        seedTenant(TENANT_SAVE);
        mvc.perform(put("/api/v1/settings/messaging/templates/{kind}", "PAYROLL")
                        .with(adminAuth(TENANT_SAVE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Salarii {{month}}\",\"body\":\"Al nostru {{month}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customized").value(true))
                .andExpect(jsonPath("$.body").value("Al nostru {{month}}"));

        mvc.perform(delete("/api/v1/settings/messaging/templates/{kind}", "PAYROLL")
                        .with(adminAuth(TENANT_SAVE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customized").value(false))
                .andExpect(jsonPath("$.body").value(
                        org.hamcrest.Matchers.containsString("fluturașul")));
    }

    @Test
    void previewRendersSampleData() throws Exception {
        seedTenant(TENANT_PREV);
        mvc.perform(post("/api/v1/settings/messaging/templates/{kind}/preview",
                        "REMINDER_DOCS_MISSING")
                        .with(adminAuth(TENANT_PREV)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"S {{month}}\",\"body\":\"Lista:\\n{{missingTransactions}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("S Aprilie 2026"))
                .andExpect(jsonPath("$.body").value(
                        org.hamcrest.Matchers.containsString("Furnizor Alfa SRL")));
    }
}
