package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * Cross-tenant isolation for {@code tenant_message_template} (golden rule #1): with RLS active and
 * the app connecting as the non-superuser {@code myfinance_app} role, tenant B must not read, update,
 * delete, or forge inserts for tenant A's templates — even without any application {@code WHERE tenant_id}
 * filter. Requires Docker; skipped when unavailable so {@code mvn test} stays green without it.
 */
class MessageTemplateIsolationTest {

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
        seedTemplate(TENANT_A, "TAX", "corp A");
        seedTemplate(TENANT_B, "TAX", "corp B");

        appDataSource = buildDataSource("myfinance_app", "myfinance_app");
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void tenantBCannotReadTenantATemplates() throws Exception {
        int visible = asTenant(TENANT_B, c -> count(c, "SELECT count(*) FROM tenant_message_template"));
        assertThat(visible).isEqualTo(1); // only B's own row, never A's
        String body = asTenant(TENANT_B, c -> single(c,
                "SELECT body FROM tenant_message_template WHERE kind = 'TAX'"));
        assertThat(body).isEqualTo("corp B");
    }

    @Test
    void tenantBCannotUpdateTenantATemplates() throws Exception {
        int updated = asTenant(TENANT_B, c -> exec(c,
                "UPDATE tenant_message_template SET body = 'hijacked' WHERE tenant_id = '" + TENANT_A + "'"));
        assertThat(updated).isZero();
        String aBody = asTenant(TENANT_A, c -> single(c,
                "SELECT body FROM tenant_message_template WHERE kind = 'TAX'"));
        assertThat(aBody).isEqualTo("corp A");
    }

    @Test
    void tenantBCannotDeleteTenantATemplates() throws Exception {
        int deleted = asTenant(TENANT_B, c -> exec(c,
                "DELETE FROM tenant_message_template WHERE tenant_id = '" + TENANT_A + "'"));
        assertThat(deleted).isZero();
    }

    @Test
    void tenantBCannotInsertForTenantA() throws Exception {
        assertThatThrownBy(() -> asTenant(TENANT_B, c -> exec(c,
                "INSERT INTO tenant_message_template (tenant_id, kind, body) VALUES ('"
                        + TENANT_A + "', 'REPORT', 'forged')")))
                .hasMessageContaining("row-level security");
    }

    // ---- helpers -------------------------------------------------------

    private static void seedTenant(String tenantId, String name) throws Exception {
        try (Connection admin = adminDataSource().getConnection();
             Statement st = admin.createStatement()) {
            st.execute("INSERT INTO tenant (id, name, status, plan) VALUES ('"
                    + tenantId + "', '" + name + "', 'ACTIVE', 'STANDARD')");
        }
    }

    private static void seedTemplate(String tenantId, String kind, String body) throws Exception {
        try (Connection c = adminDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO tenant_message_template (tenant_id, kind, subject, body) VALUES (?::uuid, ?, ?, ?)")) {
            ps.setString(1, tenantId);
            ps.setString(2, kind);
            ps.setString(3, "Subiect " + tenantId.charAt(0));
            ps.setString(4, body);
            ps.executeUpdate();
        }
    }

    /** Bind the RLS tenant the way RlsDataSource does, then run the query as myfinance_app. */
    private static <T> T asTenant(String tenantId, java.util.function.Function<Connection, T> work) throws Exception {
        try (Connection c = appDataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
                ps.setString(1, tenantId);
                ps.execute();
            }
            return work.apply(c);
        }
    }

    private static int count(Connection c, String sql) {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String single(Connection c, String sql) {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getString(1);
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int exec(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            return st.executeUpdate(sql);
        } catch (Exception e) {
            throw new RuntimeException(e);
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
