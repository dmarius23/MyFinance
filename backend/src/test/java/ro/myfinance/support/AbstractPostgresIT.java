package ro.myfinance.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for Spring integration tests that need a real Postgres with RLS. The app datasource connects
 * as the non-superuser myfinance_app role (so RLS is enforced); Flyway migrates as postgres.
 * @Testcontainers(disabledWithoutDocker = true) skips these tests cleanly when Docker is absent.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractPostgresIT {

    // @Container lets the Testcontainers extension own the lifecycle and honor disabledWithoutDocker.
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("myfinance")
                    .withUsername("postgres")
                    .withPassword("postgres")
                    .withInitScript("db/test-init-roles.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // App datasource = RLS-subject role
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "myfinance_app");
        registry.add("spring.datasource.password", () -> "myfinance_app");
        // Flyway = admin role for DDL
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "postgres");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        // No Supabase creds in tests → LoggingUserInviter is active.
        registry.add("myfinance.supabase.service-role-key", () -> "");
        // Run the post-upload document pipeline inline (still in its own post-commit transaction) so
        // integration tests that assert extraction/reconciliation results stay deterministic.
        registry.add("myfinance.async.inline", () -> "true");
    }
}
