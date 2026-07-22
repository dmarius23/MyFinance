package ro.myfinance.settings;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.myfinance.settings.adapter.persistence.PlatformTreasuryAccountRepository;
import ro.myfinance.settings.application.AnafIbanSource;
import ro.myfinance.settings.application.TreasuryIbans;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * The SUPER_ADMIN sync API end-to-end over MockMvc + Postgres, with a fake scraper (no network). Because
 * {@code myfinance.async.inline=true}, the crawl runs synchronously inside POST, so the run is already
 * READY_FOR_REVIEW when the request returns. Also asserts the /admin/** authorization boundary.
 */
@AutoConfigureMockMvc
class AnafTreasurySyncControllerIT extends AbstractPostgresIT {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeAnafIbanSource fakeAnafIbanSource() {
            return new FakeAnafIbanSource();
        }
    }

    static class FakeAnafIbanSource implements AnafIbanSource {
        volatile List<TreasuryIbans> results = List.of();

        @Override
        public List<TreasuryIbans> fetchAll() {
            return results;
        }
    }

    @Autowired MockMvc mvc;
    @Autowired FakeAnafIbanSource fakeSource;
    @Autowired PlatformTreasuryAccountRepository treasuryRepo;

    private String residence;

    @AfterEach
    void cleanup() {
        // Remove the treasury rows this test applied; sync runs are left (cheap, and other ITs don't read them).
        treasuryRepo.findAll().stream()
                .filter(a -> a.getResidence() != null && a.getResidence().startsWith("SyncTest-"))
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

    private void stageOneTreasury() {
        residence = "SyncTest-" + UUID.randomUUID();
        fakeSource.results = List.of(new TreasuryIbans("Alba", "TREZ002", residence,
                "https://anaf/iban_TREZ001_TREZ002.pdf", "RO31TREZ0025503XXXXXXXXX",
                "RO02TREZ00220A470300XXXX", "RO77TREZ00220A100101XTVA", "RO24TREZ00220A100102XTVA", null));
    }

    @Test
    void superAdminRunsReviewsAndAppliesASync() throws Exception {
        stageOneTreasury();

        String started = mvc.perform(post("/api/v1/admin/treasury-sync").with(superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"effectiveFrom\":\"2099-01-01\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("READY_FOR_REVIEW")) // inline crawl already finished
                .andExpect(jsonPath("$.treasuriesTotal").value(1))
                .andReturn().getResponse().getContentAsString();
        String runId = started.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/v1/admin/treasury-sync/{id}", runId).with(superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_REVIEW"));

        mvc.perform(get("/api/v1/admin/treasury-sync/{id}/items", runId).param("change", "ADDED").with(superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].change").value("ADDED"))
                .andExpect(jsonPath("$[0].residence").value(residence))
                .andExpect(jsonPath("$[0].iban5503").value("RO31TREZ0025503XXXXXXXXX"));

        mvc.perform(post("/api/v1/admin/treasury-sync/{id}/apply", runId).with(superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        var acc = treasuryRepo.findByResidenceAndValidFrom(residence, java.time.LocalDate.of(2099, 1, 1)).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(acc.getIbanCas()).isEqualTo("RO31TREZ0025503XXXXXXXXX");
        org.assertj.core.api.Assertions.assertThat(acc.getIbanTvaExtern()).isEqualTo("RO24TREZ00220A100102XTVA");
    }

    @Test
    void applyingATwiceRejectsTheSecondApply() throws Exception {
        stageOneTreasury();
        String started = mvc.perform(post("/api/v1/admin/treasury-sync").with(superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"effectiveFrom\":\"2099-01-01\"}"))
                .andReturn().getResponse().getContentAsString();
        String runId = started.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(post("/api/v1/admin/treasury-sync/{id}/apply", runId).with(superAdmin()))
                .andExpect(status().isOk());
        // second apply: run is APPLIED, not READY_FOR_REVIEW -> 409
        mvc.perform(post("/api/v1/admin/treasury-sync/{id}/apply", runId).with(superAdmin()))
                .andExpect(status().isConflict());
    }

    @Test
    void tenantAdminIsForbidden() throws Exception {
        mvc.perform(post("/api/v1/admin/treasury-sync").with(tenantAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/admin/treasury-sync"))
                .andExpect(status().isUnauthorized());
    }
}
