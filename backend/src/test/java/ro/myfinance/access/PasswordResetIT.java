package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.access.application.AuthUserDirectory;
import ro.myfinance.access.application.PasswordResetService;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Self-service password reset against a real Postgres with RLS, driven the way the public endpoint drives
 * it: <b>with no TenantContext bound at all</b>.
 *
 * <p>The isolation assertions use {@code audit_entry}, which is RLS-scoped by {@code tenant_id} — so
 * <em>which tenant the audit row lands in</em> is itself the proof that the service resolved and bound the
 * correct tenant, and did not touch the other one.
 */
class PasswordResetIT extends AbstractPostgresIT {

    private final UUID TENANT_A = UUID.randomUUID();
    private final UUID TENANT_B = UUID.randomUUID();

    /**
     * Per-method random tenants are the house pattern, but they are NOT enough here: this lookup is
     * cross-tenant by design, so it also sees rows another test method left in <em>its</em> tenants in the
     * shared container. Addresses must therefore be unique per method too, and the global-count
     * assertions must be scoped to this method's tenants.
     */
    private final String SUFFIX = "-" + UUID.randomUUID() + "@client.ro";

    private String email(String local) {
        return local + SUFFIX;
    }

    @Autowired PasswordResetService passwordReset;
    @Autowired AuthUserDirectory directory;
    @Autowired @Qualifier("adminJdbcTemplate") JdbcTemplate admin;

    @BeforeEach
    void seed() {
        TenantContext.clear();   // the public endpoint has no tenant bound
        seedTenant(TENANT_A);
        seedTenant(TENANT_B);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void seedTenant(UUID tenant) {
        admin.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD')"
                + " on conflict do nothing", tenant);
    }

    private UUID seedUser(UUID tenant, String email, String status) {
        UUID id = UUID.randomUUID();
        admin.update("insert into app_user(id, tenant_id, email, name, role, status)"
                + " values (?, ?, ?, 'Ana', 'EMPLOYEE', ?)", id, tenant, email, status);
        return id;
    }

    private int resetAudits(UUID tenant, UUID userId) {
        return admin.queryForObject(
                "select count(*) from audit_entry where tenant_id = ? and action = ?"
                        + " and entity = 'user' and entity_id = ?",
                Integer.class, tenant, "PASSWORD_RESET_REQUESTED", userId);
    }

    /** The directory crosses the tenant boundary exactly once, and resolves to the owning tenant only. */
    @Test
    void resolvesTheAddressToItsOwningTenantOnly() {
        UUID userA = seedUser(TENANT_A, email("ana"), "ACTIVE");
        seedUser(TENANT_B, email("altcineva"), "ACTIVE");

        var found = directory.findByEmailAcrossTenants(email("ana"));

        assertThat(found).singleElement().satisfies(u -> {
            assertThat(u.userId()).isEqualTo(userA);
            assertThat(u.tenantId()).isEqualTo(TENANT_A);
        });
    }

    /** Email is case-insensitive, as addresses are. */
    @Test
    void matchesTheAddressCaseInsensitively() {
        UUID userA = seedUser(TENANT_A, email("Ana.Pop"), "ACTIVE");

        assertThat(directory.findByEmailAcrossTenants(email("Ana.Pop").toUpperCase()))
                .singleElement()
                .satisfies(u -> assertThat(u.userId()).isEqualTo(userA));
    }

    /**
     * The same address in two tenants is near-impossible in production ({@code app_user.id} IS the
     * globally-unique Supabase auth user id, so two rows would collide on the PK) — but the schema's
     * uniqueness is only {@code (tenant_id, email)}, so the service must not assume a single match. Each
     * owning tenant gets its own branded mail.
     */
    @Test
    void handlesTheSameAddressInTwoTenants() {
        UUID userA = seedUser(TENANT_A, email("dublu"), "ACTIVE");
        UUID userB = seedUser(TENANT_B, email("dublu"), "ACTIVE");

        assertThat(directory.findByEmailAcrossTenants(email("dublu"))).hasSize(2);

        passwordReset.requestReset(email("dublu"));

        assertThat(resetAudits(TENANT_A, userA)).isEqualTo(1);
        assertThat(resetAudits(TENANT_B, userB)).isEqualTo(1);
    }

    /**
     * Golden rule #1 for this new data-access path: a reset for tenant A's user must be carried out
     * entirely inside tenant A — the audit row proves which tenant was bound, and tenant B sees nothing.
     */
    @Test
    void runsEntirelyInsideTheOwningTenant() {
        UUID userA = seedUser(TENANT_A, email("ana"), "ACTIVE");
        UUID userB = seedUser(TENANT_B, email("bogdan"), "ACTIVE");

        passwordReset.requestReset(email("ana"));

        assertThat(resetAudits(TENANT_A, userA)).isEqualTo(1);
        assertThat(resetAudits(TENANT_B, userA)).isZero();
        assertThat(resetAudits(TENANT_B, userB)).isZero();   // the other tenant is untouched
        assertThat(TenantContext.current()).isEmpty();        // context cleaned up afterwards
    }

    /** A deactivated user must not be able to reset their way back in. */
    @Test
    void ignoresADeactivatedUser() {
        UUID inactive = seedUser(TENANT_A, email("fost"), "INACTIVE");

        assertThat(directory.findByEmailAcrossTenants(email("fost"))).isEmpty();

        passwordReset.requestReset(email("fost"));

        assertThat(resetAudits(TENANT_A, inactive)).isZero();
    }

    /** Anti-enumeration: an unknown address is a silent no-op, not an error. */
    @Test
    void unknownAddressIsASilentNoOp() {
        assertThatCode(() -> passwordReset.requestReset(email("nimeni"))).doesNotThrowAnyException();

        // Scoped to this method's tenants — other methods share the container and leave their own rows.
        assertThat(admin.queryForObject(
                "select count(*) from audit_entry where action = 'PASSWORD_RESET_REQUESTED'"
                        + " and tenant_id in (?, ?)",
                Integer.class, TENANT_A, TENANT_B)).isZero();
    }
}
