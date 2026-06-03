# Companies & Representatives Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build create/view of client companies (incl. treasury accounts) and the representatives linked to those companies, with representative invites going through a Supabase-Auth-backed port.

**Architecture:** Spring Boot modular monolith. MOD-03 owns companies; MOD-02 owns users/access and the representative invite flow behind a `RepresentativeInviter` port (real Supabase adapter + logging fallback). Multi-tenancy is enforced by Postgres RLS via the per-connection `app.tenant_id` GUC. React/Vite frontend talks to `/api/v1` with a typed client + TanStack Query.

**Tech Stack:** Java 21, Spring Boot 3.5.9, Spring Data JPA, Flyway, Spring `RestClient`, Testcontainers (Postgres `pgvector/pgvector:pg16`), JUnit 5, `spring-security-test`; React 18 + TypeScript + Vite, TanStack Query, react-i18next.

---

## Context for the implementer

- The app connects to Postgres as the **non-superuser** `myfinance_app` role so RLS bites; Flyway migrates as the `postgres` admin role. See `backend/src/main/resources/application.yml`.
- `RlsDataSource` (`common/security`) sets `app.tenant_id`/`app.role` on each pooled connection from a `ThreadLocal` `TenantContext`. In **service-layer** tests you must call `TenantContext.set(...)` before invoking the service (same thread). In **web** tests the `TenantContextFilter` populates it from the request JWT automatically.
- There is **no Spring integration-test harness yet** — Task 1 builds it. The existing `CrossTenantIsolationTest` is raw JDBC and stays as-is.
- Docker may be absent locally. All Testcontainers tests are annotated `@Testcontainers(disabledWithoutDocker = true)` so they **skip** (not fail) without Docker; CI has Docker and runs them.
- Existing relevant files: `mod03_company/**` (Company CRUD + treasury already implemented), `mod02_access/**` (AppUser, RepresentativeLink, AccessService), `common/security/**`, `common/web/**`.

---

## Task 1: Spring integration-test harness (Testcontainers base)

**Files:**
- Create: `backend/src/test/resources/db/test-init-roles.sql`
- Create: `backend/src/test/java/ro/myfinance/support/AbstractPostgresIT.java`
- Create: `backend/src/test/java/ro/myfinance/support/HarnessSmokeIT.java`

- [ ] **Step 1: Create the container role-init script**

`backend/src/test/resources/db/test-init-roles.sql`:
```sql
-- Runs at container init (before Flyway). Creates the RLS-subject app role used by the test
-- datasource, mirroring infra/db/init/01-init-roles.sql.
CREATE ROLE myfinance_app WITH LOGIN PASSWORD 'myfinance_app';
GRANT CONNECT ON DATABASE myfinance TO myfinance_app;
```

- [ ] **Step 2: Create the abstract base test**

`backend/src/test/java/ro/myfinance/support/AbstractPostgresIT.java`:
```java
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
        // No Supabase creds in tests → LoggingRepresentativeInviter is active.
        registry.add("myfinance.supabase.service-role-key", () -> "");
    }
}
```

- [ ] **Step 3: Create a smoke test that loads the context**

`backend/src/test/java/ro/myfinance/support/HarnessSmokeIT.java`:
```java
package ro.myfinance.support;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class HarnessSmokeIT extends AbstractPostgresIT {

    @Autowired
    DataSource dataSource;

    @Test
    void contextLoadsAndConnectsAsAppRole() throws Exception {
        try (var conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery("select current_user")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("myfinance_app");
        }
    }
}
```

- [ ] **Step 4: Run it**

Run: `cd backend && mvn -B -Dtest=HarnessSmokeIT test`
Expected (with Docker): `BUILD SUCCESS`, 1 test passes. Without Docker: the class is skipped, build still succeeds.

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/resources/db/test-init-roles.sql backend/src/test/java/ro/myfinance/support/
git commit -m "test: add Testcontainers Spring integration harness (RLS-subject datasource)"
```

---

## Task 2: Company persistence + service integration test

Validates the already-built `CompanyService` is correctly tenant-scoped by RLS (create/list/get + treasury). No production code changes expected.

**Files:**
- Create: `backend/src/test/java/ro/myfinance/mod03_company/CompanyServiceIT.java`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/ro/myfinance/mod03_company/CompanyServiceIT.java`:
```java
package ro.myfinance.mod03_company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.mod03_company.application.CompanyService;
import ro.myfinance.support.AbstractPostgresIT;

class CompanyServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Autowired CompanyService companies;
    @Autowired JdbcTemplate jdbc; // uses the app (RLS) datasource

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void asTenant(UUID tenantId) {
        // Bind the tenant FIRST so RlsDataSource sets app.tenant_id on the JdbcTemplate connection.
        // Inserting the tenant row whose id == app.tenant_id satisfies the tenant RLS WITH CHECK.
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STANDARD') on conflict do nothing",
                tenantId, "T-" + tenantId);
    }

    @Test
    void createsAndListsCompaniesScopedToTenant() {
        asTenant(TENANT_A);
        companies.create("Alpha SRL", "RO-A-1", "SRL", "Cluj", "VAT_PAYER", "MONTHLY", null);

        asTenant(TENANT_B);
        companies.create("Beta SRL", "RO-B-1", "SRL", "Bucuresti", "NON_PAYER", "QUARTERLY", null);

        assertThat(companies.list()).hasSize(1);
        assertThat(companies.list().get(0).getLegalName()).isEqualTo("Beta SRL");
    }

    @Test
    void rejectsDuplicateCuiWithinTenant() {
        asTenant(TENANT_A);
        companies.create("Alpha SRL", "RO-DUP", "SRL", "Cluj", "VAT_PAYER", "MONTHLY", null);
        assertThatThrownBy(() -> companies.create("Alpha2 SRL", "RO-DUP", "SRL", "Cluj", null, null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void addsAndListsTreasuryAccounts() {
        asTenant(TENANT_A);
        var company = companies.create("Alpha SRL", "RO-TA", "SRL", "Cluj", "VAT_PAYER", "MONTHLY", null);
        companies.addTreasuryAccount(company.getId(), "VAT", "Cluj", "RO49AAAA1B31007593840000", "TVA");
        assertThat(companies.listTreasuryAccounts(company.getId())).hasSize(1);
    }
}
```

- [ ] **Step 2: Run it**

Run: `cd backend && mvn -B -Dtest=CompanyServiceIT test`
Expected: PASS (with Docker). If `seedTenant` fails on RLS, confirm the tenant policy allows `SUPER_ADMIN` role inserts (it does in `V1__baseline.sql`).

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/ro/myfinance/mod03_company/CompanyServiceIT.java
git commit -m "test: RLS-scoped CompanyService integration tests"
```

---

## Task 3: Model corrections — assigned app_user id, INVITED status, one-company-per-rep

**Files:**
- Modify: `backend/src/main/java/ro/myfinance/mod02_access/domain/UserStatus.java`
- Modify: `backend/src/main/java/ro/myfinance/mod02_access/domain/AppUser.java`
- Modify: `backend/src/main/java/ro/myfinance/mod02_access/domain/RepresentativeLink.java`
- Modify: `backend/src/main/java/ro/myfinance/mod02_access/adapter/persistence/RepresentativeLinkRepository.java`
- Modify: `backend/src/main/java/ro/myfinance/mod02_access/application/AccessService.java`
- Create: `backend/src/main/resources/db/migration/V2__representative_link_one_company.sql`
- Create: `backend/src/test/java/ro/myfinance/mod02_access/RepresentativeLinkIT.java`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/ro/myfinance/mod02_access/RepresentativeLinkIT.java`:
```java
package ro.myfinance.mod02_access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.mod02_access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.mod02_access.domain.RepresentativeLink;
import ro.myfinance.support.AbstractPostgresIT;

class RepresentativeLinkIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000aa01");

    @Autowired RepresentativeLinkRepository repLinks;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void aRepIsLinkedToExactlyOneCompany() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", TENANT);

        UUID userId = UUID.randomUUID();
        UUID company1 = UUID.randomUUID();
        UUID company2 = UUID.randomUUID();

        repLinks.save(new RepresentativeLink(TENANT, userId, company1));
        // Re-linking the same rep replaces the company (PK is user_id) rather than adding a row.
        repLinks.save(new RepresentativeLink(TENANT, userId, company2));

        assertThat(repLinks.findById(userId)).isPresent();
        assertThat(repLinks.findById(userId).get().getCompanyId()).isEqualTo(company2);
        assertThat(repLinks.count()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run it — verify it fails to compile/persist**

Run: `cd backend && mvn -B -Dtest=RepresentativeLinkIT test`
Expected: FAIL — `RepresentativeLinkRepository.findById(UUID)` does not compile (repo keyed by `Key`), and the composite PK allows two rows.

- [ ] **Step 3: Add `INVITED` to `UserStatus`**

Replace `backend/src/main/java/ro/myfinance/mod02_access/domain/UserStatus.java`:
```java
package ro.myfinance.mod02_access.domain;

public enum UserStatus {
    ACTIVE,
    INACTIVE,
    INVITED
}
```

- [ ] **Step 4: Switch `AppUser` to an assigned id**

In `backend/src/main/java/ro/myfinance/mod02_access/domain/AppUser.java`:
- Remove the imports `jakarta.persistence.GeneratedValue` and `jakarta.persistence.GenerationType`.
- Replace the id field
```java
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
```
with
```java
    @Id
    private UUID id;
```
- Replace the constructor
```java
    public AppUser(UUID tenantId, String email, String name, Role role) {
        this.tenantId = tenantId;
        this.email = email;
        this.name = name;
        this.role = role;
    }
```
with
```java
    public AppUser(UUID id, UUID tenantId, String email, String name, Role role) {
        this.id = id;
        this.tenantId = tenantId;
        this.email = email;
        this.name = name;
        this.role = role;
    }
```

- [ ] **Step 5: Make `RepresentativeLink` keyed by `user_id` only**

Replace `backend/src/main/java/ro/myfinance/mod02_access/domain/RepresentativeLink.java`:
```java
package ro.myfinance.mod02_access.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * MOD-02 — links a representative user to the single company they may access. A company has many
 * representatives; each representative belongs to exactly one company (FR-011), so the primary key
 * is the user id. Representative requests are additionally constrained to this company_id server-side.
 */
@Entity
@Table(name = "representative_link")
public class RepresentativeLink {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RepresentativeLink() {
    }

    public RepresentativeLink(UUID tenantId, UUID userId, UUID companyId) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.companyId = companyId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public void setCompanyId(UUID companyId) {
        this.companyId = companyId;
    }

    public UUID getTenantId() {
        return tenantId;
    }
}
```

- [ ] **Step 6: Update the repository to key by `UUID` and add `findByCompanyId`**

Replace `backend/src/main/java/ro/myfinance/mod02_access/adapter/persistence/RepresentativeLinkRepository.java`:
```java
package ro.myfinance.mod02_access.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.mod02_access.domain.RepresentativeLink;

public interface RepresentativeLinkRepository extends JpaRepository<RepresentativeLink, UUID> {

    List<RepresentativeLink> findByCompanyId(UUID companyId);
}
```

- [ ] **Step 7: Fix `AccessService` for the new `AppUser` constructor**

In `backend/src/main/java/ro/myfinance/mod02_access/application/AccessService.java`, replace the body of `inviteUser`:
```java
    public AppUser inviteUser(String email, String name, Role role) {
        UUID tenantId = currentTenant();
        // TODO(MOD-02): trigger Supabase invite; enforce plan-limit on active user count.
        return users.save(new AppUser(tenantId, email, name, role));
    }
```
with (generate a placeholder id until staff invites also go through Supabase):
```java
    public AppUser inviteUser(String email, String name, Role role) {
        UUID tenantId = currentTenant();
        // TODO(MOD-02): staff invites should also go through Supabase; until then mint a local id.
        return users.save(new AppUser(UUID.randomUUID(), tenantId, email, name, role));
    }
```

- [ ] **Step 8: Add the migration**

`backend/src/main/resources/db/migration/V2__representative_link_one_company.sql`:
```sql
-- FR-011: a representative is linked to exactly one company. Move the primary key from
-- (user_id, company_id) to user_id so a rep can have at most one company link.
ALTER TABLE representative_link DROP CONSTRAINT representative_link_pkey;
ALTER TABLE representative_link ADD PRIMARY KEY (user_id);
```

- [ ] **Step 9: Run the test + full compile**

Run: `cd backend && mvn -B -Dtest=RepresentativeLinkIT test && mvn -B -DskipTests test-compile`
Expected: `RepresentativeLinkIT` PASS (with Docker); full test-compile `BUILD SUCCESS`.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/ro/myfinance/mod02_access backend/src/main/resources/db/migration/V2__representative_link_one_company.sql backend/src/test/java/ro/myfinance/mod02_access/RepresentativeLinkIT.java
git commit -m "feat(mod02): assigned app_user id, INVITED status, one-company-per-rep PK"
```

---

## Task 4: RepresentativeInviter port + logging fallback + Supabase config

**Files:**
- Create: `backend/src/main/java/ro/myfinance/mod02_access/application/RepresentativeInviter.java`
- Create: `backend/src/main/java/ro/myfinance/mod02_access/adapter/external/LoggingRepresentativeInviter.java`
- Create: `backend/src/main/java/ro/myfinance/common/config/SupabaseProperties.java`
- Create: `backend/src/test/java/ro/myfinance/mod02_access/LoggingRepresentativeInviterTest.java`

- [ ] **Step 1: Write the failing test (pure unit, no DB)**

`backend/src/test/java/ro/myfinance/mod02_access/LoggingRepresentativeInviterTest.java`:
```java
package ro.myfinance.mod02_access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.security.Role;
import ro.myfinance.mod02_access.adapter.external.LoggingRepresentativeInviter;
import ro.myfinance.mod02_access.application.RepresentativeInviter.InviteClaims;

class LoggingRepresentativeInviterTest {

    @Test
    void returnsAGeneratedExternalUserId() {
        var inviter = new LoggingRepresentativeInviter();
        var result = inviter.invite("rep@client.ro",
                new InviteClaims(UUID.randomUUID(), Role.REPRESENTATIVE, UUID.randomUUID()));
        assertThat(result.externalUserId()).isNotNull();
    }
}
```

- [ ] **Step 2: Run it — verify it fails**

Run: `cd backend && mvn -B -Dtest=LoggingRepresentativeInviterTest test`
Expected: FAIL — classes `RepresentativeInviter` / `LoggingRepresentativeInviter` do not exist.

- [ ] **Step 3: Create the port**

`backend/src/main/java/ro/myfinance/mod02_access/application/RepresentativeInviter.java`:
```java
package ro.myfinance.mod02_access.application;

import java.util.UUID;
import ro.myfinance.common.security.Role;

/**
 * Port for inviting a representative through the identity provider (Supabase Auth). Implementations
 * create the auth user, attach the tenant claims, and trigger the invite email. The returned id
 * becomes the app_user primary key (the future JWT subject).
 */
public interface RepresentativeInviter {

    InvitedUser invite(String email, InviteClaims claims);

    record InviteClaims(UUID tenantId, Role role, UUID companyId) {}

    record InvitedUser(UUID externalUserId) {}
}
```

- [ ] **Step 4: Create the logging fallback adapter**

`backend/src/main/java/ro/myfinance/mod02_access/adapter/external/LoggingRepresentativeInviter.java`:
```java
package ro.myfinance.mod02_access.adapter.external;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ro.myfinance.mod02_access.application.RepresentativeInviter;

/**
 * Fallback used when no Supabase service-role key is configured (local dev, tests). Mints a random
 * id and logs the intended invite instead of calling Supabase, so the whole flow is exercisable
 * without credentials. Superseded by {@link SupabaseRepresentativeInviter} once creds are present.
 */
public class LoggingRepresentativeInviter implements RepresentativeInviter {

    private static final Logger log = LoggerFactory.getLogger(LoggingRepresentativeInviter.class);

    @Override
    public InvitedUser invite(String email, InviteClaims claims) {
        UUID id = UUID.randomUUID();
        log.info("[DEV INVITE] would invite {} as REPRESENTATIVE of company {} (tenant {}) -> {}",
                email, claims.companyId(), claims.tenantId(), id);
        return new InvitedUser(id);
    }

    @Configuration
    static class FallbackConfig {
        @Bean
        @ConditionalOnMissingBean(RepresentativeInviter.class)
        RepresentativeInviter loggingRepresentativeInviter() {
            return new LoggingRepresentativeInviter();
        }
    }
}
```

- [ ] **Step 5: Create the Supabase config properties**

`backend/src/main/java/ro/myfinance/common/config/SupabaseProperties.java`:
```java
package ro.myfinance.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Supabase server-side config. {@code serviceRoleKey} is a secret (env only) and gates the real
 * invite adapter; when blank, the logging fallback is used.
 */
@ConfigurationProperties(prefix = "myfinance.supabase")
public record SupabaseProperties(String url, String serviceRoleKey) {

    public SupabaseProperties {
        url = url == null ? "" : url;
        serviceRoleKey = serviceRoleKey == null ? "" : serviceRoleKey;
    }

    public boolean isConfigured() {
        return !serviceRoleKey.isBlank() && !url.isBlank();
    }
}
```

- [ ] **Step 6: Run the test**

Run: `cd backend && mvn -B -Dtest=LoggingRepresentativeInviterTest test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/ro/myfinance/mod02_access/application/RepresentativeInviter.java backend/src/main/java/ro/myfinance/mod02_access/adapter/external/LoggingRepresentativeInviter.java backend/src/main/java/ro/myfinance/common/config/SupabaseProperties.java backend/src/test/java/ro/myfinance/mod02_access/LoggingRepresentativeInviterTest.java
git commit -m "feat(mod02): RepresentativeInviter port + logging fallback + Supabase config"
```

---

## Task 5: Supabase invite adapter (real)

**Files:**
- Create: `backend/src/main/java/ro/myfinance/mod02_access/adapter/external/SupabaseRepresentativeInviter.java`
- Create: `backend/src/test/java/ro/myfinance/mod02_access/SupabaseRepresentativeInviterTest.java`

- [ ] **Step 1: Write the failing test (MockRestServiceServer)**

`backend/src/test/java/ro/myfinance/mod02_access/SupabaseRepresentativeInviterTest.java`:
```java
package ro.myfinance.mod02_access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ro.myfinance.common.config.SupabaseProperties;
import ro.myfinance.common.security.Role;
import ro.myfinance.mod02_access.adapter.external.SupabaseRepresentativeInviter;
import ro.myfinance.mod02_access.application.RepresentativeInviter.InviteClaims;

class SupabaseRepresentativeInviterTest {

    @Test
    void invitesViaGoTrueAndSetsAppMetadata() {
        UUID newUserId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        // 1) invite call returns the created user id
        server.expect(requestTo("https://proj.supabase.co/auth/v1/invite"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-service-key"))
                .andExpect(header("apikey", "test-service-key"))
                .andExpect(jsonPath("$.email").value("rep@client.ro"))
                .andRespond(withSuccess("{\"id\":\"" + newUserId + "\"}", MediaType.APPLICATION_JSON));

        // 2) admin update sets app_metadata claims
        server.expect(requestTo("https://proj.supabase.co/auth/v1/admin/users/" + newUserId))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.app_metadata.role").value("REPRESENTATIVE"))
                .andExpect(jsonPath("$.app_metadata.company_id").exists())
                .andExpect(jsonPath("$.app_metadata.tenant_id").exists())
                .andRespond(withSuccess("{\"id\":\"" + newUserId + "\"}", MediaType.APPLICATION_JSON));

        var props = new SupabaseProperties("https://proj.supabase.co", "test-service-key");
        var inviter = new SupabaseRepresentativeInviter(props, builder);

        var result = inviter.invite("rep@client.ro",
                new InviteClaims(UUID.randomUUID(), Role.REPRESENTATIVE, UUID.randomUUID()));

        assertThat(result.externalUserId()).isEqualTo(newUserId);
        server.verify();
    }
}
```

- [ ] **Step 2: Run it — verify it fails**

Run: `cd backend && mvn -B -Dtest=SupabaseRepresentativeInviterTest test`
Expected: FAIL — `SupabaseRepresentativeInviter` does not exist.

- [ ] **Step 3: Implement the adapter**

`backend/src/main/java/ro/myfinance/mod02_access/adapter/external/SupabaseRepresentativeInviter.java`:
```java
package ro.myfinance.mod02_access.adapter.external;

import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.myfinance.common.config.SupabaseProperties;
import ro.myfinance.mod02_access.application.RepresentativeInviter;

/**
 * Invites a representative through Supabase Auth (GoTrue) admin REST:
 *   1. POST /auth/v1/invite  -> creates the user, sends the invite email, returns the id
 *   2. PUT  /auth/v1/admin/users/{id} -> sets app_metadata {tenant_id, role, company_id}, which the
 *      custom access-token hook lifts into top-level JWT claims read by the backend.
 *
 * Active only when myfinance.supabase.service-role-key is set.
 */
@Component
@ConditionalOnProperty(prefix = "myfinance.supabase", name = "service-role-key")
public class SupabaseRepresentativeInviter implements RepresentativeInviter {

    private final RestClient client;

    public SupabaseRepresentativeInviter(SupabaseProperties props, RestClient.Builder builder) {
        this.client = builder
                .baseUrl(props.url())
                .defaultHeader("apikey", props.serviceRoleKey())
                .defaultHeader("Authorization", "Bearer " + props.serviceRoleKey())
                .build();
    }

    @Override
    public InvitedUser invite(String email, InviteClaims claims) {
        GoTrueUser created = client.post()
                .uri("/auth/v1/invite")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email))
                .retrieve()
                .body(GoTrueUser.class);

        if (created == null || created.id() == null) {
            throw new IllegalStateException("Supabase invite returned no user id");
        }

        client.put()
                .uri("/auth/v1/admin/users/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("app_metadata", Map.of(
                        "tenant_id", claims.tenantId().toString(),
                        "role", claims.role().name(),
                        "company_id", claims.companyId().toString())))
                .retrieve()
                .toBodilessEntity();

        return new InvitedUser(created.id());
    }

    record GoTrueUser(UUID id) {}
}
```

- [ ] **Step 4: Run the test**

Run: `cd backend && mvn -B -Dtest=SupabaseRepresentativeInviterTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/ro/myfinance/mod02_access/adapter/external/SupabaseRepresentativeInviter.java backend/src/test/java/ro/myfinance/mod02_access/SupabaseRepresentativeInviterTest.java
git commit -m "feat(mod02): Supabase GoTrue representative invite adapter"
```

---

## Task 6: Minimal append-only audit recorder

**Files:**
- Create: `backend/src/main/java/ro/myfinance/common/audit/AuditEntry.java`
- Create: `backend/src/main/java/ro/myfinance/common/audit/AuditRepository.java`
- Create: `backend/src/main/java/ro/myfinance/common/audit/AuditRecorder.java`

> The `audit_entry` table already exists (`V1__baseline.sql`). This is a minimal recorder used by the invite flow; the full MOD-12 audit module comes later.

- [ ] **Step 1: Create the entity**

`backend/src/main/java/ro/myfinance/common/audit/AuditEntry.java`:
```java
package ro.myfinance.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** MOD-12 (minimal) — append-only audit record. Tenant-scoped by RLS. */
@Entity
@Table(name = "audit_entry")
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_role")
    private String actorRole;

    @Column(nullable = false)
    private String action;

    private String entity;

    @Column(name = "entity_id")
    private UUID entityId;

    @CreationTimestamp
    @Column(name = "at", nullable = false, updatable = false)
    private Instant at;

    protected AuditEntry() {
    }

    public AuditEntry(UUID tenantId, UUID actorId, String actorRole, String action, String entity, UUID entityId) {
        this.tenantId = tenantId;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.action = action;
        this.entity = entity;
        this.entityId = entityId;
    }

    public UUID getId() {
        return id;
    }
}
```

- [ ] **Step 2: Create the repository**

`backend/src/main/java/ro/myfinance/common/audit/AuditRepository.java`:
```java
package ro.myfinance.common.audit;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEntry, UUID> {
}
```

- [ ] **Step 3: Create the recorder**

`backend/src/main/java/ro/myfinance/common/audit/AuditRecorder.java`:
```java
package ro.myfinance.common.audit;

import java.util.UUID;
import org.springframework.stereotype.Component;
import ro.myfinance.common.security.TenantContext;

/** Writes audit entries using the current tenant identity. No-ops if no tenant is bound. */
@Component
public class AuditRecorder {

    private final AuditRepository repository;

    public AuditRecorder(AuditRepository repository) {
        this.repository = repository;
    }

    public void record(String action, String entity, UUID entityId) {
        TenantContext.current().ifPresent(id -> repository.save(new AuditEntry(
                id.tenantId(), id.userId(), id.role() == null ? null : id.role().name(),
                action, entity, entityId)));
    }
}
```

- [ ] **Step 4: Compile**

Run: `cd backend && mvn -B -DskipTests test-compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/ro/myfinance/common/audit/
git commit -m "feat(audit): minimal append-only audit recorder"
```

---

## Task 7: RepresentativeService — invite + list

**Files:**
- Create: `backend/src/main/java/ro/myfinance/mod02_access/application/RepresentativeService.java`
- Create: `backend/src/test/java/ro/myfinance/mod02_access/RepresentativeServiceIT.java`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/ro/myfinance/mod02_access/RepresentativeServiceIT.java`:
```java
package ro.myfinance.mod02_access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.mod02_access.application.RepresentativeService;
import ro.myfinance.mod02_access.domain.AppUser;
import ro.myfinance.mod02_access.domain.UserStatus;
import ro.myfinance.mod03_company.application.CompanyService;
import ro.myfinance.support.AbstractPostgresIT;

class RepresentativeServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("cccccccc-0000-0000-0000-0000000000c1");

    @Autowired RepresentativeService representatives;
    @Autowired CompanyService companies;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    private UUID asTenantWithCompany() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", TENANT);
        return companies.create("Client SRL", "RO-REP-" + UUID.randomUUID(), "SRL", "Cluj", "VAT_PAYER", "MONTHLY", null).getId();
    }

    @Test
    void invitesARepresentativeAndLinksToCompany() {
        UUID companyId = asTenantWithCompany();

        AppUser rep = representatives.inviteRepresentative(companyId, "rep@client.ro", "Rep One");

        assertThat(rep.getRole()).isEqualTo(Role.REPRESENTATIVE);
        assertThat(rep.getStatus()).isEqualTo(UserStatus.INVITED);
        assertThat(representatives.listRepresentatives(companyId)).extracting(AppUser::getEmail)
                .containsExactly("rep@client.ro");
    }

    @Test
    void invitingToAMissingCompanyFails() {
        asTenantWithCompany();
        assertThatThrownBy(() -> representatives.inviteRepresentative(UUID.randomUUID(), "x@y.ro", "X"))
                .isInstanceOf(ro.myfinance.common.web.NotFoundException.class);
    }

    @Test
    void reinvitingSameEmailToSecondCompanyMovesTheRep() {
        UUID company1 = asTenantWithCompany();
        UUID company2 = companies.create("Second SRL", "RO-REP2-" + UUID.randomUUID(), "SRL", "Cluj", null, null, null).getId();
        AppUser rep = representatives.inviteRepresentative(company1, "dup@client.ro", "Dup");

        // A different invite produces a different external id (new auth user) for the same email →
        // attaching a second app_user with the same email in the tenant must be rejected.
        assertThatThrownBy(() -> representatives.inviteRepresentative(company2, "dup@client.ro", "Dup"))
                .isInstanceOf(ConflictException.class);
        assertThat(rep.getEmail()).isEqualTo("dup@client.ro");
    }
}
```

- [ ] **Step 2: Run it — verify it fails**

Run: `cd backend && mvn -B -Dtest=RepresentativeServiceIT test`
Expected: FAIL — `RepresentativeService` does not exist.

- [ ] **Step 3: Implement the service**

`backend/src/main/java/ro/myfinance/mod02_access/application/RepresentativeService.java`:
```java
package ro.myfinance.mod02_access.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.mod02_access.adapter.persistence.AppUserRepository;
import ro.myfinance.mod02_access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.mod02_access.application.RepresentativeInviter.InviteClaims;
import ro.myfinance.mod02_access.domain.AppUser;
import ro.myfinance.mod02_access.domain.RepresentativeLink;
import ro.myfinance.mod02_access.domain.UserStatus;
import ro.myfinance.mod03_company.adapter.persistence.CompanyRepository;

/**
 * MOD-02 — managing a company's representatives. Invites go through the {@link RepresentativeInviter}
 * port (Supabase or logging fallback). All reads/writes are RLS-scoped to the caller's tenant.
 */
@Service
@Transactional
public class RepresentativeService {

    private final CompanyRepository companies;
    private final AppUserRepository users;
    private final RepresentativeLinkRepository links;
    private final RepresentativeInviter inviter;
    private final AuditRecorder audit;

    public RepresentativeService(CompanyRepository companies, AppUserRepository users,
                                 RepresentativeLinkRepository links, RepresentativeInviter inviter,
                                 AuditRecorder audit) {
        this.companies = companies;
        this.users = users;
        this.links = links;
        this.inviter = inviter;
        this.audit = audit;
    }

    public AppUser inviteRepresentative(UUID companyId, String email, String name) {
        UUID tenantId = currentTenant();
        companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
        if (users.existsByEmail(email)) {
            throw new ConflictException("A user with email " + email + " already exists in this tenant");
        }

        var invited = inviter.invite(email, new InviteClaims(tenantId, Role.REPRESENTATIVE, companyId));
        AppUser rep = users.save(new AppUser(invited.externalUserId(), tenantId, email, name, Role.REPRESENTATIVE));
        rep.setStatus(UserStatus.INVITED);
        links.save(new RepresentativeLink(tenantId, rep.getId(), companyId));
        audit.record("REPRESENTATIVE_INVITED", "company", companyId);
        return rep;
    }

    @Transactional(readOnly = true)
    public List<AppUser> listRepresentatives(UUID companyId) {
        companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
        List<UUID> userIds = links.findByCompanyId(companyId).stream()
                .map(RepresentativeLink::getUserId).toList();
        return userIds.isEmpty() ? List.of() : users.findAllById(userIds);
    }

    private UUID currentTenant() {
        return TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to request"));
    }
}
```

- [ ] **Step 4: Add `existsByEmail` and `setStatus`**

In `backend/src/main/java/ro/myfinance/mod02_access/adapter/persistence/AppUserRepository.java`, add the method (keep the existing `countByRoleAndStatus`):
```java
    boolean existsByEmail(String email);
```
Confirm `AppUser` already has `setStatus(UserStatus)` (it does). No change needed there.

- [ ] **Step 5: Run the test**

Run: `cd backend && mvn -B -Dtest=RepresentativeServiceIT test`
Expected: PASS (with Docker).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/ro/myfinance/mod02_access/application/RepresentativeService.java backend/src/main/java/ro/myfinance/mod02_access/adapter/persistence/AppUserRepository.java backend/src/test/java/ro/myfinance/mod02_access/RepresentativeServiceIT.java
git commit -m "feat(mod02): RepresentativeService invite + list"
```

---

## Task 8: Representative REST endpoints (+ web integration test)

**Files:**
- Create: `backend/src/main/java/ro/myfinance/mod02_access/adapter/web/RepresentativeDtos.java`
- Create: `backend/src/main/java/ro/myfinance/mod02_access/adapter/web/RepresentativeController.java`
- Create: `backend/src/test/java/ro/myfinance/mod02_access/RepresentativeControllerIT.java`

- [ ] **Step 1: Write the failing web test**

`backend/src/test/java/ro/myfinance/mod02_access/RepresentativeControllerIT.java`:
```java
package ro.myfinance.mod02_access;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.support.AbstractPostgresIT;

@AutoConfigureMockMvc
class RepresentativeControllerIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("dddddddd-0000-0000-0000-0000000000d1");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    /** Seeds a tenant + company via TenantContext (so RLS passes), then clears it so the MockMvc
     *  request can bind its own identity from the JWT. */
    private UUID seedCompany() {
        seedTenantRow(TENANT);
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            UUID companyId = UUID.randomUUID();
            jdbc.update("insert into company(id, tenant_id, legal_name, cui, status) values (?, ?, 'C', ?, 'ACTIVE')",
                    companyId, TENANT, "RO-" + companyId);
            return companyId;
        } finally {
            TenantContext.clear();
        }
    }

    private void seedTenantRow(UUID tenantId) {
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", tenantId);
        } finally {
            TenantContext.clear();
        }
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT.toString())
                        .claim("role", "TENANT_ADMIN"))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TENANT_ADMIN"));
    }

    @Test
    void invitesAndListsRepresentatives() throws Exception {
        UUID companyId = seedCompany();

        mvc.perform(post("/api/v1/companies/{id}/representatives", companyId)
                        .with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"rep@client.ro\",\"name\":\"Rep One\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("rep@client.ro"))
                .andExpect(jsonPath("$.status").value("INVITED"));

        mvc.perform(get("/api/v1/companies/{id}/representatives", companyId).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("rep@client.ro"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/companies/{id}/representatives", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run it — verify it fails**

Run: `cd backend && mvn -B -Dtest=RepresentativeControllerIT test`
Expected: FAIL — endpoints return 404/no controller.

- [ ] **Step 3: Create the DTOs**

`backend/src/main/java/ro/myfinance/mod02_access/adapter/web/RepresentativeDtos.java`:
```java
package ro.myfinance.mod02_access.adapter.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import ro.myfinance.mod02_access.domain.AppUser;
import ro.myfinance.mod02_access.domain.UserStatus;

public final class RepresentativeDtos {

    private RepresentativeDtos() {
    }

    public record InviteRepresentativeRequest(@Email @NotBlank String email, String name) {
    }

    public record RepresentativeResponse(UUID id, String email, String name, UserStatus status) {
        public static RepresentativeResponse from(AppUser u) {
            return new RepresentativeResponse(u.getId(), u.getEmail(), u.getName(), u.getStatus());
        }
    }
}
```

- [ ] **Step 4: Create the controller**

`backend/src/main/java/ro/myfinance/mod02_access/adapter/web/RepresentativeController.java`:
```java
package ro.myfinance.mod02_access.adapter.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.mod02_access.adapter.web.RepresentativeDtos.InviteRepresentativeRequest;
import ro.myfinance.mod02_access.adapter.web.RepresentativeDtos.RepresentativeResponse;
import ro.myfinance.mod02_access.application.RepresentativeService;

/** MOD-02 — representatives of a company. Firm staff only. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/representatives")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class RepresentativeController {

    private final RepresentativeService service;

    public RepresentativeController(RepresentativeService service) {
        this.service = service;
    }

    @GetMapping
    public List<RepresentativeResponse> list(@PathVariable UUID companyId) {
        return service.listRepresentatives(companyId).stream().map(RepresentativeResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RepresentativeResponse invite(@PathVariable UUID companyId,
                                         @Valid @RequestBody InviteRepresentativeRequest request) {
        return RepresentativeResponse.from(
                service.inviteRepresentative(companyId, request.email(), request.name()));
    }
}
```

- [ ] **Step 5: Run the test**

Run: `cd backend && mvn -B -Dtest=RepresentativeControllerIT test`
Expected: PASS (with Docker).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/ro/myfinance/mod02_access/adapter/web/RepresentativeController.java backend/src/main/java/ro/myfinance/mod02_access/adapter/web/RepresentativeDtos.java backend/src/test/java/ro/myfinance/mod02_access/RepresentativeControllerIT.java
git commit -m "feat(mod02): representative invite/list REST endpoints"
```

---

## Task 9: Config wiring — application.yml + .env.example

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `.env.example`

- [ ] **Step 1: Add Supabase server props to `application.yml`**

In `backend/src/main/resources/application.yml`, under the existing top-level `myfinance:` block (which currently has `cors:`), add a `supabase:` child so the block reads:
```yaml
myfinance:
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:5173}
  supabase:
    url: ${SUPABASE_URL:}
    service-role-key: ${SUPABASE_SERVICE_ROLE_KEY:}
```

- [ ] **Step 2: Add the secret to `.env.example`**

In `.env.example`, under the `--- Auth (Supabase) ---` section, add:
```bash
# Server-side Supabase (backend) — used to invite representatives via the Auth admin API.
SUPABASE_URL=https://YOUR-PROJECT.supabase.co
# SERVICE ROLE KEY IS A SECRET — never commit a real value.
SUPABASE_SERVICE_ROLE_KEY=your-service-role-key
```

- [ ] **Step 3: Verify the app still compiles and properties bind**

Run: `cd backend && mvn -B -Dtest=HarnessSmokeIT test`
Expected: PASS (context loads with the new properties).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/application.yml .env.example
git commit -m "chore(config): Supabase server-side url + service-role-key"
```

---

## Task 10: Cross-tenant isolation for representatives (web layer)

**Files:**
- Modify: `backend/src/test/java/ro/myfinance/mod02_access/RepresentativeControllerIT.java`

- [ ] **Step 1: Add a cross-tenant test method**

Append this test to `RepresentativeControllerIT` (it reuses `seedCompany`/`staff`; add a second tenant constant at the top of the class: `private static final UUID OTHER_TENANT = UUID.fromString("eeeeeeee-0000-0000-0000-0000000000e1");`):
```java
    @Test
    void cannotListRepresentativesOfAnotherTenantsCompany() throws Exception {
        UUID companyId = seedCompany(); // belongs to TENANT

        var otherTenantJwt = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", OTHER_TENANT.toString())
                        .claim("role", "TENANT_ADMIN"))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TENANT_ADMIN"));

        // Seed the other tenant row so the request has a valid (but different) tenant context.
        seedTenantRow(OTHER_TENANT);

        mvc.perform(get("/api/v1/companies/{id}/representatives", companyId).with(otherTenantJwt))
                .andExpect(status().isNotFound()); // RLS hides the company → NotFound
    }
```

- [ ] **Step 2: Run the whole test class**

Run: `cd backend && mvn -B -Dtest=RepresentativeControllerIT test`
Expected: PASS — the other tenant gets 404 because RLS hides the company.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/ro/myfinance/mod02_access/RepresentativeControllerIT.java
git commit -m "test(mod02): cross-tenant isolation for representatives"
```

---

## Task 11: Full backend test sweep

- [ ] **Step 1: Run all backend tests**

Run: `cd backend && mvn -B test`
Expected: `BUILD SUCCESS`. With Docker, all IT classes run; without Docker, IT classes are skipped and unit tests pass.

- [ ] **Step 2: Commit (only if anything changed)**

No commit if clean.

---

## Task 12: Frontend — API client functions + query hooks

**Files:**
- Create: `frontend/src/api/companies.ts`
- Create: `frontend/src/api/representatives.ts`

- [ ] **Step 1: Create the companies API module**

`frontend/src/api/companies.ts`:
```ts
import { api } from "../lib/apiClient";

export interface Company {
  id: string;
  legalName: string;
  cui: string;
  entityType: string | null;
  locality: string | null;
  vatStatus: string | null;
  vatPeriod: string | null;
  responsibleUserId: string | null;
  status: "ACTIVE" | "INACTIVE";
}

export interface CreateCompanyInput {
  legalName: string;
  cui: string;
  entityType?: string;
  locality?: string;
  vatStatus?: string;
  vatPeriod?: string;
  responsibleUserId?: string;
}

export interface TreasuryAccount {
  id: string;
  taxType: string;
  locality: string | null;
  iban: string;
  label: string | null;
}

export const companiesApi = {
  list: () => api<Company[]>("/api/v1/companies"),
  get: (id: string) => api<Company>(`/api/v1/companies/${id}`),
  create: (input: CreateCompanyInput) =>
    api<Company>("/api/v1/companies", { method: "POST", body: JSON.stringify(input) }),
  update: (id: string, input: Partial<CreateCompanyInput>) =>
    api<Company>(`/api/v1/companies/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  setStatus: (id: string, status: "ACTIVE" | "INACTIVE") =>
    api<Company>(`/api/v1/companies/${id}/status`, { method: "PATCH", body: JSON.stringify({ status }) }),
  listTreasury: (id: string) => api<TreasuryAccount[]>(`/api/v1/companies/${id}/treasury-accounts`),
  addTreasury: (id: string, input: { taxType: string; locality?: string; iban: string; label?: string }) =>
    api<TreasuryAccount>(`/api/v1/companies/${id}/treasury-accounts`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
};
```

- [ ] **Step 2: Create the representatives API module**

`frontend/src/api/representatives.ts`:
```ts
import { api } from "../lib/apiClient";

export interface Representative {
  id: string;
  email: string;
  name: string | null;
  status: "ACTIVE" | "INACTIVE" | "INVITED";
}

export const representativesApi = {
  list: (companyId: string) =>
    api<Representative[]>(`/api/v1/companies/${companyId}/representatives`),
  invite: (companyId: string, input: { email: string; name?: string }) =>
    api<Representative>(`/api/v1/companies/${companyId}/representatives`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
};
```

- [ ] **Step 3: Type-check**

Run: `cd frontend && npx tsc -b`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/api/
git commit -m "feat(fe): typed API modules for companies and representatives"
```

---

## Task 13: Frontend — Companies list with Add-company modal

**Files:**
- Create: `frontend/src/components/AddCompanyModal.tsx`
- Modify: `frontend/src/pages/Companies.tsx`

- [ ] **Step 1: Create the modal**

`frontend/src/components/AddCompanyModal.tsx`:
```tsx
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { companiesApi, type CreateCompanyInput } from "../api/companies";
import { ApiError } from "../lib/apiClient";

export function AddCompanyModal({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const [form, setForm] = useState<CreateCompanyInput>({ legalName: "", cui: "" });
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: () => companiesApi.create(form),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["companies"] });
      onClose();
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Failed to create company"),
  });

  const set = (k: keyof CreateCompanyInput) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [k]: e.target.value }));

  return (
    <div style={overlay} onClick={onClose}>
      <form
        className="card"
        style={{ width: 460 }}
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          setError(null);
          mutation.mutate();
        }}
      >
        <h2 style={{ marginTop: 0 }}>Add company</h2>
        <Field label="Legal name *"><input required value={form.legalName} onChange={set("legalName")} /></Field>
        <Field label="CUI *"><input required value={form.cui} onChange={set("cui")} /></Field>
        <Field label="Entity type"><input value={form.entityType ?? ""} onChange={set("entityType")} placeholder="SRL" /></Field>
        <Field label="Locality"><input value={form.locality ?? ""} onChange={set("locality")} /></Field>
        <Field label="VAT status"><input value={form.vatStatus ?? ""} onChange={set("vatStatus")} placeholder="VAT_PAYER" /></Field>
        <Field label="VAT period"><input value={form.vatPeriod ?? ""} onChange={set("vatPeriod")} placeholder="MONTHLY" /></Field>
        {error && <p style={{ color: "#dc2626" }}>{error}</p>}
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 12 }}>
          <button type="button" onClick={onClose}>Cancel</button>
          <button className="primary" type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? "Saving…" : "Create"}
          </button>
        </div>
      </form>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label style={{ display: "block", marginBottom: 10 }}>
      <span style={{ display: "block", color: "var(--text-muted)", fontSize: 13 }}>{label}</span>
      {children}
    </label>
  );
}

const overlay: React.CSSProperties = {
  position: "fixed",
  inset: 0,
  background: "rgba(15,23,42,0.4)",
  display: "grid",
  placeItems: "center",
  zIndex: 50,
};
```

Add to `frontend/src/index.css` (so modal inputs are full width):
```css
.card label input {
  width: 100%;
  padding: 8px;
  border: 1px solid var(--border);
  border-radius: 8px;
  margin-top: 4px;
}
```

- [ ] **Step 2: Update the Companies page**

Replace `frontend/src/pages/Companies.tsx` with a version that uses the API module, links rows to detail, and adds the modal:
```tsx
import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { ApiError } from "../lib/apiClient";
import { AddCompanyModal } from "../components/AddCompanyModal";

/** MOD-03 — manage companies: list + add; rows link to detail. */
export function Companies() {
  const [showAdd, setShowAdd] = useState(false);
  const { data, isLoading, error } = useQuery({
    queryKey: ["companies"],
    queryFn: companiesApi.list,
  });

  return (
    <div className="card">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h1 style={{ marginTop: 0 }}>Companies</h1>
        <button className="primary" onClick={() => setShowAdd(true)}>Add company</button>
      </div>

      {isLoading && <p>Loading…</p>}
      {error && (
        <p style={{ color: "#dc2626" }}>
          {error instanceof ApiError ? error.message : "Failed to load companies"}
        </p>
      )}

      {data && (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8 }}>Legal name</th>
              <th style={{ padding: 8 }}>CUI</th>
              <th style={{ padding: 8 }}>Type</th>
              <th style={{ padding: 8 }}>Locality</th>
              <th style={{ padding: 8 }}>VAT</th>
              <th style={{ padding: 8 }}>Status</th>
            </tr>
          </thead>
          <tbody>
            {data.map((c) => (
              <tr key={c.id} style={{ borderTop: "1px solid var(--border)" }}>
                <td style={{ padding: 8 }}><Link to={`/companies/${c.id}`}>{c.legalName}</Link></td>
                <td style={{ padding: 8 }}>{c.cui}</td>
                <td style={{ padding: 8 }}>{c.entityType ?? "—"}</td>
                <td style={{ padding: 8 }}>{c.locality ?? "—"}</td>
                <td style={{ padding: 8 }}>{c.vatStatus ?? "—"}</td>
                <td style={{ padding: 8 }}>{c.status}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {showAdd && <AddCompanyModal onClose={() => setShowAdd(false)} />}
    </div>
  );
}
```

- [ ] **Step 3: Type-check + build**

Run: `cd frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/AddCompanyModal.tsx frontend/src/pages/Companies.tsx frontend/src/index.css
git commit -m "feat(fe): companies list with add-company modal"
```

---

## Task 14: Frontend — Company detail page (general info, treasury, representatives)

**Files:**
- Create: `frontend/src/pages/CompanyDetail.tsx`
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Create the detail page**

`frontend/src/pages/CompanyDetail.tsx`:
```tsx
import { useState } from "react";
import { useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { representativesApi } from "../api/representatives";
import { ApiError } from "../lib/apiClient";

/** MOD-03 — company detail: general info, treasury accounts, representatives. */
export function CompanyDetail() {
  const { id = "" } = useParams();
  const company = useQuery({ queryKey: ["company", id], queryFn: () => companiesApi.get(id) });
  const treasury = useQuery({ queryKey: ["treasury", id], queryFn: () => companiesApi.listTreasury(id) });
  const reps = useQuery({ queryKey: ["reps", id], queryFn: () => representativesApi.list(id) });

  if (company.isLoading) return <div className="card">Loading…</div>;
  if (company.error)
    return <div className="card"><p style={{ color: "#dc2626" }}>
      {company.error instanceof ApiError ? company.error.message : "Failed to load company"}</p></div>;

  const c = company.data!;
  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="card">
        <h1 style={{ marginTop: 0 }}>{c.legalName}</h1>
        <dl style={grid}>
          <Row k="CUI" v={c.cui} />
          <Row k="Entity type" v={c.entityType ?? "—"} />
          <Row k="Locality" v={c.locality ?? "—"} />
          <Row k="VAT status" v={c.vatStatus ?? "—"} />
          <Row k="VAT period" v={c.vatPeriod ?? "—"} />
          <Row k="Status" v={c.status} />
        </dl>
      </div>

      <TreasurySection companyId={id} accounts={treasury.data ?? []} />
      <RepresentativesSection companyId={id} />
      <span hidden>{reps.isLoading ? "" : ""}</span>
    </div>
  );
}

function TreasurySection({ companyId, accounts }: { companyId: string; accounts: { id: string; taxType: string; iban: string; label: string | null }[] }) {
  const qc = useQueryClient();
  const [form, setForm] = useState({ taxType: "", iban: "", label: "" });
  const add = useMutation({
    mutationFn: () => companiesApi.addTreasury(companyId, form),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["treasury", companyId] });
      setForm({ taxType: "", iban: "", label: "" });
    },
  });
  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>Treasury accounts</h2>
      <ul>
        {accounts.map((a) => (
          <li key={a.id}>{a.taxType} — {a.iban} {a.label ? `(${a.label})` : ""}</li>
        ))}
        {accounts.length === 0 && <li style={{ color: "var(--text-muted)" }}>None yet</li>}
      </ul>
      <form style={{ display: "flex", gap: 8, marginTop: 8 }} onSubmit={(e) => { e.preventDefault(); add.mutate(); }}>
        <input placeholder="Tax type" required value={form.taxType} onChange={(e) => setForm({ ...form, taxType: e.target.value })} />
        <input placeholder="IBAN" required value={form.iban} onChange={(e) => setForm({ ...form, iban: e.target.value })} />
        <input placeholder="Label" value={form.label} onChange={(e) => setForm({ ...form, label: e.target.value })} />
        <button className="primary" type="submit" disabled={add.isPending}>Add</button>
      </form>
    </div>
  );
}

function RepresentativesSection({ companyId }: { companyId: string }) {
  const qc = useQueryClient();
  const reps = useQuery({ queryKey: ["reps", companyId], queryFn: () => representativesApi.list(companyId) });
  const [form, setForm] = useState({ email: "", name: "" });
  const [error, setError] = useState<string | null>(null);
  const invite = useMutation({
    mutationFn: () => representativesApi.invite(companyId, form),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["reps", companyId] });
      setForm({ email: "", name: "" });
      setError(null);
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Invite failed"),
  });
  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>Representatives</h2>
      {reps.isLoading && <p>Loading…</p>}
      <ul>
        {(reps.data ?? []).map((r) => (
          <li key={r.id}>{r.name ?? r.email} — {r.email} <span style={pill}>{r.status}</span></li>
        ))}
        {reps.data?.length === 0 && <li style={{ color: "var(--text-muted)" }}>No representatives yet</li>}
      </ul>
      {error && <p style={{ color: "#dc2626" }}>{error}</p>}
      <form style={{ display: "flex", gap: 8, marginTop: 8 }} onSubmit={(e) => { e.preventDefault(); invite.mutate(); }}>
        <input type="email" placeholder="email" required value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
        <input placeholder="name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
        <button className="primary" type="submit" disabled={invite.isPending}>Invite</button>
      </form>
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (<>
    <dt style={{ color: "var(--text-muted)" }}>{k}</dt>
    <dd style={{ margin: 0 }}>{v}</dd>
  </>);
}

const grid: React.CSSProperties = { display: "grid", gridTemplateColumns: "160px 1fr", rowGap: 8 };
const pill: React.CSSProperties = { background: "var(--border)", borderRadius: 999, padding: "2px 8px", fontSize: 12 };
```

- [ ] **Step 2: Add the route**

In `frontend/src/App.tsx`:
- Add the import near the other page imports:
```tsx
import { CompanyDetail } from "./pages/CompanyDetail";
```
- Add the route immediately after the `/companies` route inside the firm-app `FirmLayout` block:
```tsx
          <Route path="/companies/:id" element={<CompanyDetail />} />
```

- [ ] **Step 3: Build**

Run: `cd frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/CompanyDetail.tsx frontend/src/App.tsx
git commit -m "feat(fe): company detail page (general info, treasury, representatives)"
```

---

## Task 15: Frontend — i18n strings + lint

**Files:**
- Modify: `frontend/src/i18n.ts`

- [ ] **Step 1: Add labels to both catalogs**

In `frontend/src/i18n.ts`, add these keys to the `ro.translation` object:
```ts
      "companies.add": "Adaugă firmă",
      "company.representatives": "Reprezentanți",
      "company.treasury": "Conturi trezorerie",
      "company.invite": "Invită reprezentant",
```
and the same keys to `en.translation`:
```ts
      "companies.add": "Add company",
      "company.representatives": "Representatives",
      "company.treasury": "Treasury accounts",
      "company.invite": "Invite representative",
```

> These keys are provided for use as the UI is refined; the components above use literal English for brevity. Wiring `t()` calls into the components is optional polish, not required for this slice.

- [ ] **Step 2: Lint + build**

Run: `cd frontend && npm run lint && npm run build`
Expected: both succeed.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/i18n.ts
git commit -m "feat(fe): i18n strings for companies & representatives"
```

---

## Task 16: Supabase access-token hook SQL + docs

**Files:**
- Create: `infra/supabase/access_token_hook.sql`
- Modify: `README.md`

- [ ] **Step 1: Create the hook SQL**

`infra/supabase/access_token_hook.sql`:
```sql
-- Supabase custom access-token hook: lifts app_metadata.{tenant_id, role, company_id} into
-- top-level JWT claims that the backend reads (SupabaseJwtAuthoritiesConverter / TenantContextFilter).
-- Apply in the Supabase project (SQL editor), then enable under Authentication -> Hooks ->
-- Customize Access Token, pointing at public.custom_access_token_hook.
create or replace function public.custom_access_token_hook(event jsonb)
returns jsonb
language plpgsql
stable
as $$
declare
  claims    jsonb;
  meta      jsonb;
begin
  claims := event->'claims';
  meta   := coalesce(claims->'app_metadata', '{}'::jsonb);

  if meta ? 'tenant_id' then
    claims := jsonb_set(claims, '{tenant_id}', meta->'tenant_id');
  end if;
  if meta ? 'role' then
    claims := jsonb_set(claims, '{role}', meta->'role');
  end if;
  if meta ? 'company_id' then
    claims := jsonb_set(claims, '{company_id}', meta->'company_id');
  end if;

  event := jsonb_set(event, '{claims}', claims);
  return event;
end;
$$;

grant usage on schema public to supabase_auth_admin;
grant execute on function public.custom_access_token_hook to supabase_auth_admin;
revoke execute on function public.custom_access_token_hook from authenticated, anon, public;
```

- [ ] **Step 2: Document it in the README**

In `README.md`, under the auth note in the Quickstart section, add a bullet:
```markdown
- **Representative invites** use the Supabase Auth admin API: set `SUPABASE_URL` + `SUPABASE_SERVICE_ROLE_KEY` (backend) and apply `infra/supabase/access_token_hook.sql` in your Supabase project, then enable it under Authentication → Hooks. Until configured, invites use a local logging fallback.
```

- [ ] **Step 3: Commit**

```bash
git add infra/supabase/access_token_hook.sql README.md
git commit -m "docs(supabase): access-token hook SQL + invite setup notes"
```

---

## Task 17: Final verification

- [ ] **Step 1: Backend full build**

Run: `cd backend && mvn -B verify`
Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Frontend lint + build**

Run: `cd frontend && npm run lint && npm run build`
Expected: both succeed.

- [ ] **Step 3: Manual smoke (only if Docker available)**

```bash
docker compose up -d db redis
cd backend && SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
# In another shell, hit Swagger UI at http://localhost:8080/swagger-ui.html
cd frontend && npm run dev   # open http://localhost:5173/companies
```
Note: API calls 401 until Supabase auth is configured; representative invites use the logging fallback (check backend logs) until `SUPABASE_SERVICE_ROLE_KEY` is set.

---

## Self-review notes (addressed)

- **Spec coverage:** companies create/view (Tasks 2,13,14), treasury (Tasks 2,14), rep invite via port (Tasks 4,5,7,8), one-company-per-rep (Task 3), app_user id = auth id (Task 3), access-token hook (Task 16), cross-tenant isolation (Tasks 2,10), config/secrets (Task 9). All present.
- **Type consistency:** `RepresentativeInviter.InvitedUser.externalUserId()`, `InviteClaims(tenantId, role, companyId)`, `RepresentativeResponse(id,email,name,status)`, and the frontend `Representative`/`Company` types match across tasks.
- **Deferred (explicitly out of scope):** tax-profile matrix, deadlines, other detail cards, edit/remove rep, resend invite, frontend test runner.
