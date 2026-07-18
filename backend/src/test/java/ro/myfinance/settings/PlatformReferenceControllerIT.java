package ro.myfinance.settings;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.myfinance.settings.adapter.persistence.PlatformTaxRateRepository;
import ro.myfinance.settings.adapter.persistence.PlatformTreasuryAccountRepository;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * The global-reference admin API is SUPER_ADMIN-only. This is the mandatory authz test the design
 * brief calls for: a SUPER_ADMIN can manage the reference data; a non-SUPER_ADMIN (TENANT_ADMIN)
 * cannot. No cross-tenant isolation test — these tables are intentionally global (see V35).
 * Rows created here (marked with far-future effective dates / a unique residence) are cleaned up so
 * they don't leak into other ITs sharing the container.
 */
@AutoConfigureMockMvc
class PlatformReferenceControllerIT extends AbstractPostgresIT {

    private static final LocalDate FUTURE = LocalDate.of(2099, 1, 1);

    @Autowired MockMvc mvc;
    @Autowired PlatformTaxRateRepository rateRepo;
    @Autowired PlatformTreasuryAccountRepository treasuryRepo;

    @AfterEach
    void cleanup() {
        rateRepo.findAll().stream().filter(r -> r.getValidFrom().getYear() >= 2090)
                .forEach(r -> rateRepo.deleteById(r.getId()));
        treasuryRepo.findAll().stream().filter(a -> a.getResidence().startsWith("AdminTest-"))
                .forEach(a -> treasuryRepo.deleteById(a.getId()));
    }

    private static RequestPostProcessor superAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "SUPER_ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    }

    private static RequestPostProcessor tenantAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", UUID.randomUUID().toString()).claim("role", "TENANT_ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"));
    }

    @Test
    void superAdminCreatesAndListsATaxRate() throws Exception {
        mvc.perform(post("/api/v1/admin/reference/tax-rates")
                        .with(superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"VAT\",\"rate\":23.00,\"validFrom\":\"2099-01-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("VAT"))
                .andExpect(jsonPath("$.rate").value(23.00));

        mvc.perform(get("/api/v1/admin/reference/tax-rates").with(superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.validFrom=='2099-01-01' && @.category=='VAT')]").exists());
    }

    @Test
    void duplicateEffectiveDateIsRejected() throws Exception {
        String body = "{\"category\":\"MICRO\",\"rate\":1.00,\"validFrom\":\"2099-01-01\"}";
        mvc.perform(post("/api/v1/admin/reference/tax-rates").with(superAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/admin/reference/tax-rates").with(superAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    }

    @Test
    void superAdminCreatesTreasuryAndCanDeleteIt() throws Exception {
        String residence = "AdminTest-" + UUID.randomUUID();
        String created = mvc.perform(post("/api/v1/admin/reference/treasury-accounts")
                        .with(superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"residence\":\"" + residence + "\",\"validFrom\":\"2099-01-01\","
                                + "\"ibanTva\":\"RO00TEST0000000000000001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.residence").value(residence))
                .andExpect(jsonPath("$.ibanTva").value("RO00TEST0000000000000001"))
                .andReturn().getResponse().getContentAsString();

        String id = created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(delete("/api/v1/admin/reference/treasury-accounts/{id}", id).with(superAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void tenantAdminCannotReadOrWriteReferenceData() throws Exception {
        mvc.perform(post("/api/v1/admin/reference/tax-rates")
                        .with(tenantAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"VAT\",\"rate\":50.00,\"validFrom\":\"2099-01-01\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/admin/reference/tax-rates").with(tenantAdmin()))
                .andExpect(status().isForbidden());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/admin/reference/tax-rates"))
                .andExpect(status().isUnauthorized());
    }
}
