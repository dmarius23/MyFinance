package ro.myfinance.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;
import java.util.UUID;
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
 * Per-tenant messaging provider settings: secrets are AES-GCM encrypted at rest and write-only over the
 * API (responses expose only {@code hasPassword}/{@code hasToken}), and every read/write is RLS-scoped so
 * one firm never sees another's provider config.
 */
@AutoConfigureMockMvc
class MessagingSettingsControllerIT extends AbstractPostgresIT {

    // A dedicated tenant per test — these ITs commit and don't roll back, so a shared tenant would let
    // one test's provider mutation bleed into another's assertions (and test order isn't guaranteed).
    private static final UUID TENANT_ENC = UUID.fromString("eeeeeeee-0000-0000-0000-0000000000e1");
    private static final UUID TENANT_KEEP = UUID.fromString("eeeeeeee-0000-0000-0000-0000000000e2");
    private static final UUID TENANT_WA = UUID.fromString("eeeeeeee-0000-0000-0000-0000000000e3");
    private static final UUID TENANT_A = UUID.fromString("eeeeeeee-0000-0000-0000-0000000000a1");
    private static final UUID TENANT_B = UUID.fromString("eeeeeeee-0000-0000-0000-0000000000b1");

    @DynamicPropertySource
    static void secretKey(DynamicPropertyRegistry registry) {
        // A deterministic 32-byte AES key for the test (all-zero bytes, base64-encoded).
        registry.add("myfinance.secret.key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

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

    private static RequestPostProcessor admin(UUID tenant) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", tenant.toString()).claim("role", "TENANT_ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"));
    }

    @Test
    void emailSecretIsEncryptedAtRestAndNeverReturned() throws Exception {
        seedTenant(TENANT_ENC);
        mvc.perform(put("/api/v1/settings/messaging/email")
                        .with(admin(TENANT_ENC)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"fromEmail":"contact@firma-a.ro","fromName":"Firma A",
                                 "smtpHost":"smtp.resend.com","smtpPort":587,"smtpUsername":"resend",
                                 "smtpPassword":"super-secret-pw"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.smtpPassword").doesNotExist());

        // GET never leaks the secret, only its presence.
        mvc.perform(get("/api/v1/settings/messaging/email").with(admin(TENANT_ENC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.smtpHost").value("smtp.resend.com"))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.smtpPassword").doesNotExist());

        // Stored ciphertext is not the plaintext.
        assertThat(passwordEnc(TENANT_ENC)).isNotBlank().isNotEqualTo("super-secret-pw");
    }

    @Test
    void blankPasswordKeepsTheStoredSecret() throws Exception {
        seedTenant(TENANT_KEEP);
        mvc.perform(put("/api/v1/settings/messaging/email").with(admin(TENANT_KEEP))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"fromEmail":"a@firma.ro","smtpHost":"smtp.x","smtpPort":587,
                                 "smtpPassword":"keep-me"}"""))
                .andExpect(status().isOk());
        String before = passwordEnc(TENANT_KEEP);

        // Update other fields with no password → secret preserved.
        mvc.perform(put("/api/v1/settings/messaging/email").with(admin(TENANT_KEEP))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"fromEmail":"a@firma.ro","smtpHost":"smtp.y","smtpPort":465}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.smtpHost").value("smtp.y"));
        assertThat(passwordEnc(TENANT_KEEP)).isEqualTo(before);
    }

    @Test
    void whatsappTokenStoredWriteOnly() throws Exception {
        seedTenant(TENANT_WA);
        mvc.perform(put("/api/v1/settings/messaging/whatsapp").with(admin(TENANT_WA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"TWILIO","accountSid":"AC123","authToken":"tok-secret","fromNumber":"+14155238886"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("TWILIO"))
                .andExpect(jsonPath("$.hasToken").value(true))
                .andExpect(jsonPath("$.authToken").doesNotExist());
    }

    @Test
    void oneTenantNeverSeesAnothersProviderConfig() throws Exception {
        seedTenant(TENANT_A);
        seedTenant(TENANT_B);
        mvc.perform(put("/api/v1/settings/messaging/email").with(admin(TENANT_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"fromEmail":"a@firma.ro","smtpHost":"smtp.a","smtpPort":587,
                                 "smtpPassword":"a-pw"}"""))
                .andExpect(status().isOk());

        // Tenant B sees empty defaults, NOT tenant A's config.
        mvc.perform(get("/api/v1/settings/messaging/email").with(admin(TENANT_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.smtpHost").doesNotExist())
                .andExpect(jsonPath("$.hasPassword").value(false));
    }

    private String passwordEnc(UUID tenant) {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            return jdbc.queryForObject(
                    "select smtp_password_enc from tenant_email_provider where tenant_id = ?",
                    String.class, tenant);
        } finally {
            TenantContext.clear();
        }
    }
}
