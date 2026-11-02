package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ro.myfinance.common.messaging.DefaultMessageTemplates;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.MessageTemplateService;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Proves the rendering path is tenant-isolated at the service layer (golden rule #1): with tenant A
 * bound, a send renders A's wording, and B's override is invisible — and vice versa.
 *
 * <p>Each test method gets its own pair of tenant UUIDs (random, unique per instance) to avoid
 * cross-method bleed on the shared Testcontainers database, which does not roll back between tests.
 *
 * <p>How each assertion can fail if scoping is broken:
 * <ul>
 *   <li>{@code eachTenantRendersItsOwnWording} — A's body is "Textul firmei A", B's is "Textul firmei B".
 *       If service ignores the tenant binding and reads any row, the bodies would be swapped or equal.
 *   <li>{@code aTenantWithNoOverrideStillGetsTheDefaultNotAnotherTenantsText} — A saves "Doar A.";
 *       B asserts the built-in Romanian phrase ("Vă transmitem atașat raportul financiar") and explicitly
 *       asserts B's result does NOT contain "Doar A.". A cross-tenant leak would make B.body() == "Doar A."
 *       and the {@code doesNotContain} assertion would fail.
 *   <li>{@code effectiveAllNeverLeaksAnotherTenantsOverride} — A saves a secret phrase "Secretul firmei A";
 *       B calls effectiveAll() and asserts none of the 6 entries contain that phrase. A leak would
 *       propagate the phrase and the {@code noneMatch} would fail.
 *   <li>{@code resetOnlyAffectsTheBoundTenant} — after B resets PAYROLL, A must still render "Textul A",
 *       and B must be uncustomized. Furthermore, B's body must be exactly the built-in PAYROLL default
 *       (containing "fluturașul") and must NOT contain "Textul A". If reset deleted A's row, A.body()
 *       would fall back to the default instead of "Textul A". If reset corrupted B's row instead of
 *       deleting it (empty or broken body), the customized flag would still be false but the body would
 *       not match the default text.
 * </ul>
 */
class TemplateRenderingIsolationIT extends AbstractPostgresIT {

    // Per-instance UUIDs: JUnit creates a fresh class instance per test method, so each method gets
    // its own tenant pair and cannot bleed into another method's assertions on the shared DB.
    private final UUID TENANT_A = UUID.randomUUID();
    private final UUID TENANT_B = UUID.randomUUID();

    @DynamicPropertySource
    static void secretKey(DynamicPropertyRegistry registry) {
        // Deterministic 32-byte AES key (all-zero bytes, base64-encoded) — same pattern as
        // TemplateSettingsAuthzIT.
        registry.add("myfinance.secret.key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired MessageTemplateService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedTenants() {
        // Seed both tenant rows by binding each tenant's context, then inserting via JdbcTemplate.
        // The insert succeeds because the tenant table has RLS enabled with a policy that permits
        // writes to the tenant's own row (when app.tenant_id matches the tenant_id column).
        // The myfinance_app role is subject to RLS but permitted to insert its own tenant.
        TenantContext.set(new TenantContext.Identity(TENANT_A, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            jdbc.update(
                    "insert into tenant(id, name, status, plan) values (?, 'Firma A SRL', 'ACTIVE', 'STD') on conflict do nothing",
                    TENANT_A);
        } finally {
            TenantContext.clear();
        }
        TenantContext.set(new TenantContext.Identity(TENANT_B, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            jdbc.update(
                    "insert into tenant(id, name, status, plan) values (?, 'Firma B SRL', 'ACTIVE', 'STD') on conflict do nothing",
                    TENANT_B);
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void clearTenantContext() {
        // Belt-and-suspenders: the asTenant helper always clears in a finally, but clear here too in
        // case a test throws before the finally runs or the assertion failure leaves the context set.
        TenantContext.clear();
    }

    // --- tests ---

    @Test
    void eachTenantRendersItsOwnWording() {
        asTenant(TENANT_A, () -> { service.save(MessageTemplateKind.PAYROLL, "A subiect {{month}}",
                "Textul firmei A pentru {{month}}."); return null; });
        asTenant(TENANT_B, () -> { service.save(MessageTemplateKind.PAYROLL, "B subiect {{month}}",
                "Textul firmei B pentru {{month}}."); return null; });

        var a = asTenant(TENANT_A, () -> service.render(MessageTemplateKind.PAYROLL,
                Map.of("month", "Aprilie 2026")));
        var b = asTenant(TENANT_B, () -> service.render(MessageTemplateKind.PAYROLL,
                Map.of("month", "Aprilie 2026")));

        assertThat(a.body()).isEqualTo("Textul firmei A pentru Aprilie 2026.");
        assertThat(a.subject()).isEqualTo("A subiect Aprilie 2026");
        assertThat(b.body()).isEqualTo("Textul firmei B pentru Aprilie 2026.");
        assertThat(b.subject()).isEqualTo("B subiect Aprilie 2026");
    }

    @Test
    void aTenantWithNoOverrideStillGetsTheDefaultNotAnotherTenantsText() {
        asTenant(TENANT_A, () -> { service.save(MessageTemplateKind.REPORT, "A", "Doar A."); return null; });
        var b = asTenant(TENANT_B, () -> service.effective(MessageTemplateKind.REPORT));
        assertThat(b.customized()).isFalse();
        assertThat(b.body()).contains("Vă transmitem atașat raportul financiar");
        assertThat(b.body()).doesNotContain("Doar A.");
    }

    @Test
    void effectiveAllNeverLeaksAnotherTenantsOverride() {
        asTenant(TENANT_A, () -> { service.save(MessageTemplateKind.TAX, "A", "Secretul firmei A"); return null; });
        var all = asTenant(TENANT_B, () -> service.effectiveAll());
        assertThat(all).hasSize(MessageTemplateKind.values().length);
        assertThat(all).noneMatch(e -> e.body().contains("Secretul firmei A"));
        assertThat(all).allMatch(e -> !e.customized());
    }

    @Test
    void resetOnlyAffectsTheBoundTenant() {
        asTenant(TENANT_A, () -> { service.save(MessageTemplateKind.PAYROLL, "A", "Textul A"); return null; });
        asTenant(TENANT_B, () -> { service.save(MessageTemplateKind.PAYROLL, "B", "Textul B"); return null; });
        asTenant(TENANT_B, () -> { service.reset(MessageTemplateKind.PAYROLL); return null; });

        assertThat(asTenant(TENANT_A, () -> service.effective(MessageTemplateKind.PAYROLL)).body())
                .isEqualTo("Textul A");
        var tenantBAfterReset = asTenant(TENANT_B, () -> service.effective(MessageTemplateKind.PAYROLL));
        assertThat(tenantBAfterReset.customized())
                .isFalse();
        // After reset, tenant B's body is the built-in PAYROLL default, which contains the word
        // "fluturașul" (the salary slip). This strong assertion fails if reset() corrupted the row
        // (leaving empty or broken body) instead of deleting it, in which case the flag would still
        // read false but the body would not match the default.
        assertThat(tenantBAfterReset.body())
                .contains("fluturașul")
                .isEqualTo(DefaultMessageTemplates.of(MessageTemplateKind.PAYROLL).body());
        // Also assert that tenant B does NOT contain tenant A's distinctive text, mirroring the
        // strong form used in aTenantWithNoOverrideStillGetsTheDefaultNotAnotherTenantsText.
        assertThat(tenantBAfterReset.body())
                .doesNotContain("Textul A");
    }

    // --- helper ---

    /**
     * Binds the given tenant identity, runs {@code work}, and always clears the context — even if work
     * throws. This is the ThreadLocal-safety contract: a leaked binding contaminates every subsequent
     * database operation in the same JVM fork.
     */
    private <T> T asTenant(UUID tenantId, Supplier<T> work) {
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }
}
