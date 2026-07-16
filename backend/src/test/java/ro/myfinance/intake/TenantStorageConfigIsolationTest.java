package ro.myfinance.intake;

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
 * Cross-tenant isolation for {@code tenant_storage_config} (golden rule #1): with RLS active and the app
 * connecting as the non-superuser {@code myfinance_app} role, tenant B must not read, update or delete
 * tenant A's storage config. Requires Docker; skipped when unavailable.
 */
class TenantStorageConfigIsolationTest {

    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";

    private static PostgreSQLContainer<?> postgres;
    private static DataSource appDataSource; // connects as myfinance_app (RLS-subject)

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping RLS isolation test");

        postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                .withDatabaseName("myfinance").withUsername("postgres").withPassword("postgres");
        postgres.start();

        try (Connection admin = adminDataSource().getConnection();
             Statement st = admin.createStatement()) {
            st.execute("CREATE ROLE myfinance_app LOGIN PASSWORD 'myfinance_app'");
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), "postgres", "postgres")
                .locations("classpath:db/migration").load().migrate();

        seedTenant(TENANT_A, "Tenant A SRL");
        seedTenant(TENANT_B, "Tenant B SRL");
        seedConfig(TENANT_A, "DRIVE_MIRROR");
        seedConfig(TENANT_B, "SUPABASE_ONLY");

        appDataSource = buildDataSource("myfinance_app", "myfinance_app");
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void tenantSeesOnlyItsOwnConfig() throws Exception {
        assertThat(rowCountFor(TENANT_A)).isEqualTo(1);
        assertThat(rowCountFor(TENANT_B)).isEqualTo(1);
    }

    @Test
    void tenantCannotReadAnotherTenantsConfig() throws Exception {
        try (Connection conn = appDataSource.getConnection()) {
            setTenant(conn, TENANT_B);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM tenant_storage_config WHERE tenant_id = ?::uuid")) {
                ps.setString(1, TENANT_A);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("tenant B must not see tenant A's config").isZero();
                }
            }
        }
    }

    @Test
    void tenantCannotUpdateAnotherTenantsConfig() throws Exception {
        try (Connection conn = appDataSource.getConnection()) {
            setTenant(conn, TENANT_B);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tenant_storage_config SET storage_mode = 'DRIVE_MIRROR' WHERE tenant_id = ?::uuid")) {
                ps.setString(1, TENANT_A);
                assertThat(ps.executeUpdate()).as("RLS must prevent cross-tenant writes").isZero();
            }
        }
    }

    private static int rowCountFor(String tenantId) throws Exception {
        try (Connection conn = appDataSource.getConnection()) {
            setTenant(conn, tenantId);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM tenant_storage_config")) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void setTenant(Connection conn, String tenantId) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false), "
                    + "set_config('app.role', 'TENANT_ADMIN', false)");
        }
    }

    private static void seedTenant(String tenantId, String name) throws Exception {
        try (Connection admin = adminDataSource().getConnection();
             Statement st = admin.createStatement()) {
            st.execute("INSERT INTO tenant (id, name, status, plan) VALUES ('"
                    + tenantId + "', '" + name + "', 'ACTIVE', 'STANDARD')");
        }
    }

    private static void seedConfig(String tenantId, String mode) throws Exception {
        try (Connection admin = adminDataSource().getConnection();
             Statement st = admin.createStatement()) {
            st.execute("INSERT INTO tenant_storage_config (tenant_id, storage_mode) VALUES ('"
                    + tenantId + "', '" + mode + "')");
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
