package ro.myfinance.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Cross-tenant isolation for {@code push_subscription} (golden rule #1): with RLS active and the app
 * connecting as the non-superuser {@code myfinance_app} role, tenant B must not read, update, or delete
 * tenant A's push subscriptions — even with no application {@code WHERE tenant_id} filter. Requires
 * Docker; skipped when unavailable so {@code mvn test} stays green without it.
 */
class PushSubscriptionIsolationTest {

    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
    private static final String USER_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String USER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private static PostgreSQLContainer<?> postgres;
    private static DataSource appDataSource; // connects as myfinance_app (RLS-subject)

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping RLS isolation test");

        postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                .withDatabaseName("myfinance")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();

        try (Connection admin = adminDataSource().getConnection();
             Statement st = admin.createStatement()) {
            st.execute("CREATE ROLE myfinance_app LOGIN PASSWORD 'myfinance_app'");
        }

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), "postgres", "postgres")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        seedTenant(TENANT_A, "Tenant A SRL");
        seedTenant(TENANT_B, "Tenant B SRL");
        seedSubscription(TENANT_A, USER_A, "https://push.example/endpoint-A");
        seedSubscription(TENANT_B, USER_B, "https://push.example/endpoint-B");

        appDataSource = buildDataSource("myfinance_app", "myfinance_app");
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void tenantSeesOnlyItsOwnSubscriptions() throws Exception {
        assertThat(subscriptionCountFor(TENANT_A)).isEqualTo(1);
        assertThat(subscriptionCountFor(TENANT_B)).isEqualTo(1);
    }

    @Test
    void tenantCannotReadAnotherTenantsSubscription() throws Exception {
        try (Connection conn = appDataSource.getConnection()) {
            setTenant(conn, TENANT_B);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM push_subscription WHERE endpoint = ?")) {
                ps.setString(1, "https://push.example/endpoint-A");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("tenant B must not see tenant A's subscription").isZero();
                }
            }
        }
    }

    @Test
    void tenantCannotDeleteAnotherTenantsSubscription() throws Exception {
        try (Connection conn = appDataSource.getConnection()) {
            setTenant(conn, TENANT_B);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM push_subscription WHERE endpoint = ?")) {
                ps.setString(1, "https://push.example/endpoint-A");
                int deleted = ps.executeUpdate();
                assertThat(deleted).as("RLS must prevent cross-tenant deletes").isZero();
            }
        }
    }

    // ---- helpers -------------------------------------------------------

    private static int subscriptionCountFor(String tenantId) throws Exception {
        try (Connection conn = appDataSource.getConnection()) {
            setTenant(conn, tenantId);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM push_subscription")) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void setTenant(Connection conn, String tenantId) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false), "
                    + "set_config('app.role', 'REPRESENTATIVE', false)");
        }
    }

    private static void seedTenant(String tenantId, String name) throws Exception {
        try (Connection admin = adminDataSource().getConnection();
             Statement st = admin.createStatement()) {
            st.execute("INSERT INTO tenant (id, name, status, plan) VALUES ('"
                    + tenantId + "', '" + name + "', 'ACTIVE', 'STANDARD')");
        }
    }

    private static void seedSubscription(String tenantId, String userId, String endpoint) throws Exception {
        try (Connection admin = adminDataSource().getConnection();
             Statement st = admin.createStatement()) {
            st.execute("INSERT INTO push_subscription (tenant_id, user_id, endpoint, p256dh, auth) VALUES ('"
                    + tenantId + "', '" + userId + "', '" + endpoint + "', 'p256dh-key', 'auth-key')");
        }
    }

    private static DataSource adminDataSource() {
        return buildDataSource("postgres", "postgres");
    }

    private static DataSource buildDataSource(String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }
}
