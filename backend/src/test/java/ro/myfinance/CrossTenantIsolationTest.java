package ro.myfinance;

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
 * Mandatory cross-tenant isolation test (golden rule #1) at the database layer: with row-level
 * security active and the app connecting as the non-superuser {@code myfinance_app} role, a request
 * bound to tenant B must not be able to read or write tenant A's rows — even with no application
 * {@code WHERE tenant_id} filter.
 *
 * <p>Requires Docker. When Docker is unavailable the test is skipped (so {@code mvn test} stays
 * green in environments without it) rather than failing.
 */
class CrossTenantIsolationTest {

    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";

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

        // Create the RLS-subject role before migrating so the grant block applies.
        try (Connection admin = adminDataSource().getConnection();
             Statement st = admin.createStatement()) {
            st.execute("CREATE ROLE myfinance_app LOGIN PASSWORD 'myfinance_app'");
        }

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), "postgres", "postgres")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        seedTenant(TENANT_A, "Tenant A SRL", "RO1111");
        seedTenant(TENANT_B, "Tenant B SRL", "RO2222");

        appDataSource = buildDataSource("myfinance_app", "myfinance_app");
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void tenantSeesOnlyItsOwnCompanies() throws Exception {
        assertThat(companyCountFor(TENANT_A)).isEqualTo(1);
        assertThat(companyCountFor(TENANT_B)).isEqualTo(1);
    }

    @Test
    void tenantCannotReadAnotherTenantsCompanyById() throws Exception {
        String tenantACompanyId = idOfCompanyIn(TENANT_A);

        try (Connection conn = appDataSource.getConnection()) {
            setTenant(conn, TENANT_B);
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM company WHERE id = ?::uuid")) {
                ps.setString(1, tenantACompanyId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("tenant B must not see tenant A's company").isZero();
                }
            }
        }
    }

    @Test
    void tenantCannotUpdateAnotherTenantsCompany() throws Exception {
        String tenantACompanyId = idOfCompanyIn(TENANT_A);

        try (Connection conn = appDataSource.getConnection()) {
            setTenant(conn, TENANT_B);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE company SET legal_name = 'HACKED' WHERE id = ?::uuid")) {
                ps.setString(1, tenantACompanyId);
                int updated = ps.executeUpdate();
                assertThat(updated).as("RLS must prevent cross-tenant writes").isZero();
            }
        }
    }

    // ---- helpers -------------------------------------------------------

    private static int companyCountFor(String tenantId) throws Exception {
        try (Connection conn = appDataSource.getConnection()) {
            setTenant(conn, tenantId);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM company")) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String idOfCompanyIn(String tenantId) throws Exception {
        // read with admin (bypasses RLS) to fetch the real id for negative tests
        try (Connection admin = adminDataSource().getConnection();
             PreparedStatement ps = admin.prepareStatement("SELECT id FROM company WHERE tenant_id = ?::uuid")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void setTenant(Connection conn, String tenantId) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false), "
                    + "set_config('app.role', 'TENANT_ADMIN', false)");
        }
    }

    private static void seedTenant(String tenantId, String name, String cui) throws Exception {
        try (Connection admin = adminDataSource().getConnection();
             Statement st = admin.createStatement()) {
            st.execute("INSERT INTO tenant (id, name, status, plan) VALUES ('"
                    + tenantId + "', '" + name + "', 'ACTIVE', 'STANDARD')");
            st.execute("INSERT INTO company (tenant_id, legal_name, cui, status) VALUES ('"
                    + tenantId + "', '" + name + "', '" + cui + "', 'ACTIVE')");
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
