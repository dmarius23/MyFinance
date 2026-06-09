# General Settings & Company Fiscal Fields Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace per-company treasury accounts with a tenant-level settings module (VAT rate + county/tax-type treasury registry), and convert company entity-type/VAT-period/fiscal-residence fields from free-text inputs to constrained, translated dropdowns.

**Architecture:** Spring Boot modular monolith. New `ro.myfinance.settings` module owns `GeneralSettings` + `CountyTreasuryAccount` (Flyway V3 migration). Per-company `treasury_account` table and all its backend/frontend code is deleted. Company forms get constrained dropdowns backed by frontend domain lists. Settings page is TENANT_ADMIN-only.

**Tech Stack:** Java 21, Spring Boot 3.5.9, Spring Data JPA, Flyway, Testcontainers; React 18 + TypeScript + Vite, TanStack Query, react-i18next.

---

## Context for the implementer

- **Multi-tenancy:** every table has `tenant_id`. `RlsDataSource` sets `app.tenant_id` per connection from `TenantContext`. In service/IT tests, always call `TenantContext.set(...)` before any DB operation, and `TenantContext.clear()` in `@AfterEach`. See `AbstractPostgresIT` + existing `CompanyServiceIT` for the pattern.
- **Module layout:** `ro.myfinance.<module>/{domain,application,adapter/{persistence,web,external}}`. Follow this for the new `settings` module.
- **Error types:** `ConflictException` (409) and `NotFoundException` (404) are in `ro.myfinance.common.web`; `ApiExceptionHandler` maps them automatically.
- **Docker is NOT installed locally.** `*IT` tests extend `AbstractPostgresIT` with `@Testcontainers(disabledWithoutDocker = true)` — they skip locally and run in CI. Verify locally with `mvn -B -DskipTests test-compile` + `mvn -B test` (unit tests must pass; ITs skip).
- **Seed migration:** `V1000` has already been applied to the local DB. Changing it without also setting `validate-on-migrate: false` would cause a Flyway checksum error. Task 1 handles both.

---

## File map

**Create (backend):**
- `backend/src/main/resources/db/migration/V3__general_settings.sql`
- `backend/src/main/java/ro/myfinance/settings/domain/GeneralSettings.java`
- `backend/src/main/java/ro/myfinance/settings/domain/CountyTreasuryAccount.java`
- `backend/src/main/java/ro/myfinance/settings/adapter/persistence/GeneralSettingsRepository.java`
- `backend/src/main/java/ro/myfinance/settings/adapter/persistence/CountyTreasuryAccountRepository.java`
- `backend/src/main/java/ro/myfinance/settings/application/SettingsService.java`
- `backend/src/main/java/ro/myfinance/settings/adapter/web/SettingsDtos.java`
- `backend/src/main/java/ro/myfinance/settings/adapter/web/SettingsController.java`
- `backend/src/test/java/ro/myfinance/settings/SettingsServiceIT.java`

**Delete (backend):**
- `backend/src/main/java/ro/myfinance/company/domain/TreasuryAccount.java`
- `backend/src/main/java/ro/myfinance/company/adapter/persistence/TreasuryAccountRepository.java`

**Modify (backend):**
- `backend/src/main/java/ro/myfinance/company/application/CompanyService.java` — remove treasury methods + TreasuryAccountRepository dependency
- `backend/src/main/java/ro/myfinance/company/adapter/web/CompanyController.java` — remove treasury endpoints + imports
- `backend/src/main/java/ro/myfinance/company/adapter/web/CompanyDtos.java` — remove treasury records + imports
- `backend/src/test/java/ro/myfinance/company/CompanyServiceIT.java` — remove `addsAndListsTreasuryAccounts` test
- `backend/src/main/resources/db/seed/V1000__dev_seed.sql` — remove treasury_account insert, add settings seed
- `backend/src/main/resources/application-local.yml` — add `validate-on-migrate: false`

**Create (frontend):**
- `frontend/src/domain/counties.ts`
- `frontend/src/domain/taxTypes.ts`
- `frontend/src/domain/company.ts`
- `frontend/src/api/settings.ts`
- `frontend/src/pages/Settings.tsx`

**Modify (frontend):**
- `frontend/src/i18n.ts` — add all new labels
- `frontend/src/api/companies.ts` — remove treasury types and methods
- `frontend/src/components/AddCompanyModal.tsx` — entity type, VAT period, fiscal residence dropdowns
- `frontend/src/pages/CompanyDetail.tsx` — remove TreasurySection, update edit form dropdowns
- `frontend/src/components/FirmLayout.tsx` — add Settings nav entry (TENANT_ADMIN only)
- `frontend/src/App.tsx` — add `/settings` route

---

## Task 1: V3 migration + seed update + local config

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__general_settings.sql`
- Modify: `backend/src/main/resources/db/seed/V1000__dev_seed.sql`
- Modify: `backend/src/main/resources/application-local.yml`

- [ ] **Step 1: Write the migration**

`backend/src/main/resources/db/migration/V3__general_settings.sql`:
```sql
-- Tenant-level VAT rate and other general settings. One row per tenant, created lazily.
CREATE TABLE general_settings (
    tenant_id  uuid PRIMARY KEY REFERENCES tenant(id),
    vat_rate   numeric(5,2) NOT NULL DEFAULT 21.00,
    updated_at timestamptz  NOT NULL DEFAULT now()
);

ALTER TABLE general_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE general_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON general_settings
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Per-county / per-tax-type treasury accounts (replaces per-company treasury_account).
CREATE TABLE county_treasury_account (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    county    text NOT NULL,
    tax_type  text NOT NULL,
    iban      text NOT NULL,
    label     text,
    CONSTRAINT uq_county_treasury UNIQUE (tenant_id, county, tax_type)
);
CREATE INDEX idx_county_treasury_tenant ON county_treasury_account(tenant_id);

ALTER TABLE county_treasury_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE county_treasury_account FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON county_treasury_account
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Drop per-company treasury (superseded by county_treasury_account).
DROP TABLE treasury_account;

-- Grants for the RLS-subject app role.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON general_settings TO myfinance_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON county_treasury_account TO myfinance_app;
    END IF;
END $$;
```

- [ ] **Step 2: Update the seed**

Replace `backend/src/main/resources/db/seed/V1000__dev_seed.sql` entirely:
```sql
-- Dev-only demo data. Loaded only under the 'local' profile (see application-local.yml,
-- spring.flyway.locations). Sets the RLS session GUCs so inserts satisfy the policies.
SET LOCAL app.role = 'SUPER_ADMIN';
SET LOCAL app.tenant_id = '00000000-0000-0000-0000-000000000001';

INSERT INTO tenant (id, name, cui, status, plan)
VALUES ('00000000-0000-0000-0000-000000000001', 'Demo Contabilitate SRL', 'RO12345678', 'ACTIVE', 'STANDARD')
ON CONFLICT (id) DO NOTHING;

INSERT INTO app_user (id, tenant_id, email, name, role, status)
VALUES
  ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000001', 'admin@demo.ro',    'Ana Admin',    'TENANT_ADMIN', 'ACTIVE'),
  ('00000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-000000000001', 'employee@demo.ro', 'Emil Angajat', 'EMPLOYEE',     'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO company (id, tenant_id, legal_name, entity_type, cui, locality, vat_status, vat_period, responsible_user_id, status)
VALUES
  ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-000000000001', 'Client Unu SRL', 'SRL', 'RO22223333', 'Cluj',       'VAT_PAYER',     'MONTHLY',   '00000000-0000-0000-0000-0000000000a2', 'ACTIVE'),
  ('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-000000000001', 'Client Doi SRL', 'SRL', 'RO44445555', 'București',   'NON_VAT_PAYER', 'QUARTERLY', '00000000-0000-0000-0000-0000000000a2', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO general_settings (tenant_id, vat_rate)
VALUES ('00000000-0000-0000-0000-000000000001', 21.00)
ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO county_treasury_account (id, tenant_id, county, tax_type, iban, label)
VALUES
  ('00000000-0000-0000-0000-000000000e01', '00000000-0000-0000-0000-000000000001', 'Cluj', 'TVA', 'RO49AAAA1B31007593840000', 'TVA Cluj'),
  ('00000000-0000-0000-0000-000000000e02', '00000000-0000-0000-0000-000000000001', 'Cluj', 'IMPOZIT_SALARII', 'RO49AAAA1B31007593840001', 'Impozit salarii Cluj')
ON CONFLICT DO NOTHING;
```

- [ ] **Step 3: Add `validate-on-migrate: false` to the local profile**

In `backend/src/main/resources/application-local.yml`, add under the existing `spring.flyway` block:
```yaml
spring:
  flyway:
    locations: classpath:db/migration,classpath:db/seed
    validate-on-migrate: false   # V1000 checksum may differ from the updated file on already-migrated local DBs
```

- [ ] **Step 4: Verify migration compiles (no Java yet — just SQL)**

Run: `cd backend && mvn -B -DskipTests test-compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**
```bash
git add backend/src/main/resources/db/migration/V3__general_settings.sql backend/src/main/resources/db/seed/V1000__dev_seed.sql backend/src/main/resources/application-local.yml
git commit -m "feat(settings): V3 migration — general_settings + county_treasury_account, drop treasury_account"
```

---

## Task 2: Remove per-company treasury from backend

**Files:**
- Delete: `backend/src/main/java/ro/myfinance/company/domain/TreasuryAccount.java`
- Delete: `backend/src/main/java/ro/myfinance/company/adapter/persistence/TreasuryAccountRepository.java`
- Modify: `backend/src/main/java/ro/myfinance/company/application/CompanyService.java`
- Modify: `backend/src/main/java/ro/myfinance/company/adapter/web/CompanyController.java`
- Modify: `backend/src/main/java/ro/myfinance/company/adapter/web/CompanyDtos.java`
- Modify: `backend/src/test/java/ro/myfinance/company/CompanyServiceIT.java`

- [ ] **Step 1: Delete the two treasury files**
```bash
git rm backend/src/main/java/ro/myfinance/company/domain/TreasuryAccount.java
git rm backend/src/main/java/ro/myfinance/company/adapter/persistence/TreasuryAccountRepository.java
```

- [ ] **Step 2: Replace `CompanyService.java`** — remove treasury fields/methods/imports:
```java
package ro.myfinance.company.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.company.domain.CompanyStatus;

/**
 * Client company management. Tenant id always comes from {@link TenantContext}, never the client.
 * CUI is unique per tenant.
 */
@Service
@Transactional
public class CompanyService {

    private final CompanyRepository companies;

    public CompanyService(CompanyRepository companies) {
        this.companies = companies;
    }

    @Transactional(readOnly = true)
    public List<Company> list() {
        return companies.findAll();
    }

    @Transactional(readOnly = true)
    public Company get(UUID id) {
        return companies.findById(id)
                .orElseThrow(() -> new NotFoundException("Company not found: " + id));
    }

    public Company create(String legalName, String cui, String entityType, String locality,
                          String vatStatus, String vatPeriod, UUID responsibleUserId) {
        if (companies.existsByCui(cui)) {
            throw new ConflictException("A company with CUI " + cui + " already exists in this tenant");
        }
        Company company = new Company(currentTenant(), legalName, cui);
        company.setEntityType(entityType);
        company.setLocality(locality);
        company.setVatStatus(vatStatus);
        company.setVatPeriod(vatPeriod);
        company.setResponsibleUserId(responsibleUserId);
        return companies.save(company);
    }

    public Company update(UUID id, String legalName, String entityType, String locality,
                          String vatStatus, String vatPeriod, UUID responsibleUserId) {
        Company company = get(id);
        if (legalName != null) {
            company.setLegalName(legalName);
        }
        company.setEntityType(entityType);
        company.setLocality(locality);
        company.setVatStatus(vatStatus);
        company.setVatPeriod(vatPeriod);
        company.setResponsibleUserId(responsibleUserId);
        return company;
    }

    public Company setStatus(UUID id, CompanyStatus status) {
        Company company = get(id);
        company.setStatus(status);
        return company;
    }

    private UUID currentTenant() {
        return TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to request"));
    }
}
```

- [ ] **Step 3: Replace `CompanyController.java`** — remove treasury endpoints + imports:
```java
package ro.myfinance.company.adapter.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.company.adapter.web.CompanyDtos.CompanyResponse;
import ro.myfinance.company.adapter.web.CompanyDtos.CreateCompanyRequest;
import ro.myfinance.company.adapter.web.CompanyDtos.SetStatusRequest;
import ro.myfinance.company.adapter.web.CompanyDtos.UpdateCompanyRequest;
import ro.myfinance.company.application.CompanyService;

/** Client company management. Firm staff (admin/employee) only. */
@RestController
@RequestMapping("/api/v1/companies")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class CompanyController {

    private final CompanyService service;

    public CompanyController(CompanyService service) {
        this.service = service;
    }

    @GetMapping
    public List<CompanyResponse> list() {
        return service.list().stream().map(CompanyResponse::from).toList();
    }

    @GetMapping("/{id}")
    public CompanyResponse get(@PathVariable UUID id) {
        return CompanyResponse.from(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyResponse create(@Valid @RequestBody CreateCompanyRequest r) {
        return CompanyResponse.from(service.create(r.legalName(), r.cui(), r.entityType(),
                r.locality(), r.vatStatus(), r.vatPeriod(), r.responsibleUserId()));
    }

    @PutMapping("/{id}")
    public CompanyResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateCompanyRequest r) {
        return CompanyResponse.from(service.update(id, r.legalName(), r.entityType(), r.locality(),
                r.vatStatus(), r.vatPeriod(), r.responsibleUserId()));
    }

    @PatchMapping("/{id}/status")
    public CompanyResponse setStatus(@PathVariable UUID id, @Valid @RequestBody SetStatusRequest r) {
        return CompanyResponse.from(service.setStatus(id, r.status()));
    }
}
```

- [ ] **Step 4: Replace `CompanyDtos.java`** — remove treasury records:
```java
package ro.myfinance.company.adapter.web;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import ro.myfinance.company.domain.Company;
import ro.myfinance.company.domain.CompanyStatus;

public final class CompanyDtos {

    private CompanyDtos() {
    }

    public record CreateCompanyRequest(@NotBlank String legalName, @NotBlank String cui,
                                       String entityType, String locality, String vatStatus,
                                       String vatPeriod, UUID responsibleUserId) {
    }

    public record UpdateCompanyRequest(String legalName, String entityType, String locality,
                                       String vatStatus, String vatPeriod, UUID responsibleUserId) {
    }

    public record SetStatusRequest(@jakarta.validation.constraints.NotNull CompanyStatus status) {
    }

    public record CompanyResponse(UUID id, String legalName, String cui, String entityType,
                                  String locality, String vatStatus, String vatPeriod,
                                  UUID responsibleUserId, CompanyStatus status) {
        public static CompanyResponse from(Company c) {
            return new CompanyResponse(c.getId(), c.getLegalName(), c.getCui(), c.getEntityType(),
                    c.getLocality(), c.getVatStatus(), c.getVatPeriod(), c.getResponsibleUserId(),
                    c.getStatus());
        }
    }
}
```

- [ ] **Step 5: Remove the treasury test from `CompanyServiceIT.java`**

Delete the entire `addsAndListsTreasuryAccounts` test method (lines containing `void addsAndListsTreasuryAccounts`). Keep `createsAndListsCompaniesScopedToTenant` and `rejectsDuplicateCuiWithinTenant`. Also remove the now-unused `companies.addTreasuryAccount` and `companies.listTreasuryAccounts` calls. The class should look like this final state — replace the entire file:
```java
package ro.myfinance.company;

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
import ro.myfinance.company.application.CompanyService;
import ro.myfinance.support.AbstractPostgresIT;

class CompanyServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Autowired CompanyService companies;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void asTenant(UUID tenantId) {
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STANDARD') on conflict do nothing",
                tenantId, "T-" + tenantId);
    }

    @Test
    void createsAndListsCompaniesScopedToTenant() {
        asTenant(TENANT_A);
        companies.create("Alpha SRL", "RO-A-1", "SRL", "Cluj", "VAT_PAYER", "MONTHLY", null);

        asTenant(TENANT_B);
        companies.create("Beta SRL", "RO-B-1", "SRL", "București", "NON_VAT_PAYER", "QUARTERLY", null);

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
}
```

- [ ] **Step 6: Verify everything compiles and unit tests pass**

Run: `cd backend && mvn -B test`
Expected: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` (or similar), `BUILD SUCCESS`. The treasury IT test is gone; the remaining unit tests all pass; `*IT` classes skip without Docker.

- [ ] **Step 7: Commit**
```bash
git add -A
git commit -m "feat(company): remove per-company treasury (superseded by settings module)"
```

---

## Task 3: Settings module — entities + repositories

**Files:**
- Create: `backend/src/main/java/ro/myfinance/settings/domain/GeneralSettings.java`
- Create: `backend/src/main/java/ro/myfinance/settings/domain/CountyTreasuryAccount.java`
- Create: `backend/src/main/java/ro/myfinance/settings/adapter/persistence/GeneralSettingsRepository.java`
- Create: `backend/src/main/java/ro/myfinance/settings/adapter/persistence/CountyTreasuryAccountRepository.java`

- [ ] **Step 1: Create `GeneralSettings.java`**
```java
package ro.myfinance.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Tenant-level configuration — one row per tenant, created lazily on first read.
 * {@code tenant_id} is both the PK and the tenant ownership identifier; RLS enforces isolation.
 */
@Entity
@Table(name = "general_settings")
public class GeneralSettings {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "vat_rate", nullable = false)
    private BigDecimal vatRate = new BigDecimal("21.00");

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GeneralSettings() {
    }

    public GeneralSettings(UUID tenantId) {
        this.tenantId = tenantId;
        this.vatRate = new BigDecimal("21.00");
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public BigDecimal getVatRate() {
        return vatRate;
    }

    public void setVatRate(BigDecimal vatRate) {
        this.vatRate = vatRate;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
```

- [ ] **Step 2: Create `CountyTreasuryAccount.java`**
```java
package ro.myfinance.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A tenant-level treasury account for a given county + tax type combination.
 * Used by MOD-07 to resolve the correct IBAN when composing state-payment emails.
 */
@Entity
@Table(name = "county_treasury_account")
public class CountyTreasuryAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String county;

    @Column(name = "tax_type", nullable = false)
    private String taxType;

    @Column(nullable = false)
    private String iban;

    private String label;

    protected CountyTreasuryAccount() {
    }

    public CountyTreasuryAccount(UUID tenantId, String county, String taxType, String iban, String label) {
        this.tenantId = tenantId;
        this.county = county;
        this.taxType = taxType;
        this.iban = iban;
        this.label = label;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getCounty() {
        return county;
    }

    public String getTaxType() {
        return taxType;
    }

    public String getIban() {
        return iban;
    }

    public String getLabel() {
        return label;
    }
}
```

- [ ] **Step 3: Create `GeneralSettingsRepository.java`**
```java
package ro.myfinance.settings.adapter.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.settings.domain.GeneralSettings;

public interface GeneralSettingsRepository extends JpaRepository<GeneralSettings, UUID> {
}
```

- [ ] **Step 4: Create `CountyTreasuryAccountRepository.java`**
```java
package ro.myfinance.settings.adapter.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.settings.domain.CountyTreasuryAccount;

public interface CountyTreasuryAccountRepository extends JpaRepository<CountyTreasuryAccount, UUID> {

    // RLS scopes this to the current tenant automatically — checks within the tenant only.
    boolean existsByCountyAndTaxType(String county, String taxType);
}
```

- [ ] **Step 5: Compile**

Run: `cd backend && mvn -B -DskipTests test-compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**
```bash
git add backend/src/main/java/ro/myfinance/settings/
git commit -m "feat(settings): GeneralSettings + CountyTreasuryAccount entities and repositories"
```

---

## Task 4: SettingsService + SettingsDtos + SettingsController

**Files:**
- Create: `backend/src/main/java/ro/myfinance/settings/application/SettingsService.java`
- Create: `backend/src/main/java/ro/myfinance/settings/adapter/web/SettingsDtos.java`
- Create: `backend/src/main/java/ro/myfinance/settings/adapter/web/SettingsController.java`

- [ ] **Step 1: Create `SettingsService.java`**
```java
package ro.myfinance.settings.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.settings.adapter.persistence.CountyTreasuryAccountRepository;
import ro.myfinance.settings.adapter.persistence.GeneralSettingsRepository;
import ro.myfinance.settings.domain.CountyTreasuryAccount;
import ro.myfinance.settings.domain.GeneralSettings;

/**
 * Tenant-level general settings: VAT rate and the county/tax-type treasury-account registry.
 * All reads/writes are RLS-scoped; tenant_id always comes from {@link TenantContext}.
 */
@Service
@Transactional
public class SettingsService {

    private final GeneralSettingsRepository settings;
    private final CountyTreasuryAccountRepository treasuryAccounts;

    public SettingsService(GeneralSettingsRepository settings,
                           CountyTreasuryAccountRepository treasuryAccounts) {
        this.settings = settings;
        this.treasuryAccounts = treasuryAccounts;
    }

    /**
     * Returns the tenant's settings, creating a default row (vat_rate = 21.00) if none exists yet.
     * NOT read-only because it may persist a new row.
     */
    public GeneralSettings getSettings() {
        UUID tenantId = currentTenant();
        return settings.findById(tenantId)
                .orElseGet(() -> settings.save(new GeneralSettings(tenantId)));
    }

    public GeneralSettings updateVatRate(BigDecimal rate) {
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("VAT rate must be between 0 and 100");
        }
        GeneralSettings s = getSettings();
        s.setVatRate(rate);
        return s;
    }

    @Transactional(readOnly = true)
    public List<CountyTreasuryAccount> listTreasuryAccounts() {
        return treasuryAccounts.findAll();
    }

    public CountyTreasuryAccount addTreasuryAccount(String county, String taxType, String iban, String label) {
        if (treasuryAccounts.existsByCountyAndTaxType(county, taxType)) {
            throw new ConflictException(
                    "A treasury account for " + county + " / " + taxType + " already exists");
        }
        return treasuryAccounts.save(
                new CountyTreasuryAccount(currentTenant(), county, taxType, iban, label));
    }

    public void deleteTreasuryAccount(UUID id) {
        CountyTreasuryAccount account = treasuryAccounts.findById(id)
                .orElseThrow(() -> new NotFoundException("Treasury account not found: " + id));
        treasuryAccounts.delete(account);
    }

    private UUID currentTenant() {
        return TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to request"));
    }
}
```

- [ ] **Step 2: Create `SettingsDtos.java`**
```java
package ro.myfinance.settings.adapter.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import ro.myfinance.settings.domain.CountyTreasuryAccount;
import ro.myfinance.settings.domain.GeneralSettings;

public final class SettingsDtos {

    private SettingsDtos() {
    }

    public record SettingsResponse(BigDecimal vatRate) {
        public static SettingsResponse from(GeneralSettings s) {
            return new SettingsResponse(s.getVatRate());
        }
    }

    public record UpdateVatRateRequest(@NotNull BigDecimal vatRate) {
    }

    public record CreateCountyTreasuryRequest(@NotBlank String county, @NotBlank String taxType,
                                              @NotBlank String iban, String label) {
    }

    public record CountyTreasuryResponse(UUID id, String county, String taxType, String iban, String label) {
        public static CountyTreasuryResponse from(CountyTreasuryAccount a) {
            return new CountyTreasuryResponse(a.getId(), a.getCounty(), a.getTaxType(),
                    a.getIban(), a.getLabel());
        }
    }
}
```

- [ ] **Step 3: Create `SettingsController.java`**
```java
package ro.myfinance.settings.adapter.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.settings.adapter.web.SettingsDtos.CountyTreasuryResponse;
import ro.myfinance.settings.adapter.web.SettingsDtos.CreateCountyTreasuryRequest;
import ro.myfinance.settings.adapter.web.SettingsDtos.SettingsResponse;
import ro.myfinance.settings.adapter.web.SettingsDtos.UpdateVatRateRequest;
import ro.myfinance.settings.application.SettingsService;

/** Tenant-level general settings. TENANT_ADMIN only. */
@RestController
@RequestMapping("/api/v1/settings")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class SettingsController {

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping
    public SettingsResponse getSettings() {
        return SettingsResponse.from(service.getSettings());
    }

    @PutMapping
    public SettingsResponse updateVatRate(@Valid @RequestBody UpdateVatRateRequest request) {
        return SettingsResponse.from(service.updateVatRate(request.vatRate()));
    }

    @GetMapping("/treasury-accounts")
    public List<CountyTreasuryResponse> listTreasury() {
        return service.listTreasuryAccounts().stream().map(CountyTreasuryResponse::from).toList();
    }

    @PostMapping("/treasury-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public CountyTreasuryResponse addTreasury(@Valid @RequestBody CreateCountyTreasuryRequest request) {
        return CountyTreasuryResponse.from(
                service.addTreasuryAccount(request.county(), request.taxType(), request.iban(), request.label()));
    }

    @DeleteMapping("/treasury-accounts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTreasury(@PathVariable UUID id) {
        service.deleteTreasuryAccount(id);
    }
}
```

- [ ] **Step 4: Compile + unit tests**

Run: `cd backend && mvn -B test`
Expected: `BUILD SUCCESS`, 6 unit tests pass, IT classes skip.

- [ ] **Step 5: Commit**
```bash
git add backend/src/main/java/ro/myfinance/settings/
git commit -m "feat(settings): SettingsService + SettingsController (VAT rate + county treasury CRUD)"
```

---

## Task 5: SettingsServiceIT

**Files:**
- Create: `backend/src/test/java/ro/myfinance/settings/SettingsServiceIT.java`

- [ ] **Step 1: Write the test**
```java
package ro.myfinance.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.settings.application.SettingsService;
import ro.myfinance.settings.domain.CountyTreasuryAccount;
import ro.myfinance.support.AbstractPostgresIT;

class SettingsServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000011");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000022");

    @Autowired SettingsService service;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void asTenant(UUID tenantId) {
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STANDARD') on conflict do nothing",
                tenantId, "T-" + tenantId);
    }

    @Test
    void returnsDefaultVatRateWhenNoRowExists() {
        asTenant(TENANT_A);
        assertThat(service.getSettings().getVatRate()).isEqualByComparingTo("21.00");
    }

    @Test
    void getSettingsIsIdempotent() {
        asTenant(TENANT_A);
        service.getSettings();
        service.getSettings(); // second call must not throw (PK conflict)
        assertThat(service.getSettings().getVatRate()).isEqualByComparingTo("21.00");
    }

    @Test
    void updatesVatRate() {
        asTenant(TENANT_A);
        service.updateVatRate(new BigDecimal("19.00"));
        assertThat(service.getSettings().getVatRate()).isEqualByComparingTo("19.00");
    }

    @Test
    void rejectsVatRateAbove100() {
        asTenant(TENANT_A);
        assertThatThrownBy(() -> service.updateVatRate(new BigDecimal("101")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeVatRate() {
        asTenant(TENANT_A);
        assertThatThrownBy(() -> service.updateVatRate(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addsAndListsTreasuryAccounts() {
        asTenant(TENANT_A);
        service.addTreasuryAccount("Cluj", "TVA", "RO49AAAA1B31007593840000", "TVA Cluj");
        service.addTreasuryAccount("Cluj", "IMPOZIT_SALARII", "RO49AAAA1B31007593840001", "Sal Cluj");
        assertThat(service.listTreasuryAccounts()).hasSize(2);
    }

    @Test
    void rejectsDuplicateCountyTaxType() {
        asTenant(TENANT_A);
        service.addTreasuryAccount("Cluj", "TVA", "RO49AAAA1B31007593840000", "TVA Cluj");
        assertThatThrownBy(() -> service.addTreasuryAccount("Cluj", "TVA", "RO99BBBB...", "other"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deletesTreasuryAccount() {
        asTenant(TENANT_A);
        CountyTreasuryAccount account = service.addTreasuryAccount("Cluj", "TVA", "RO49...", "TVA");
        service.deleteTreasuryAccount(account.getId());
        assertThat(service.listTreasuryAccounts()).isEmpty();
    }

    @Test
    void deleteNonExistentThrowsNotFound() {
        asTenant(TENANT_A);
        assertThatThrownBy(() -> service.deleteTreasuryAccount(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void tenantBCannotSeeTenantASettings() {
        asTenant(TENANT_A);
        service.updateVatRate(new BigDecimal("5.00"));
        service.addTreasuryAccount("Cluj", "TVA", "RO49...", "TVA A");

        asTenant(TENANT_B);
        // B gets the default (not A's 5%)
        assertThat(service.getSettings().getVatRate()).isEqualByComparingTo("21.00");
        assertThat(service.listTreasuryAccounts()).isEmpty();
    }

    @Test
    void tenantBCannotDeleteTenantAAccount() {
        asTenant(TENANT_A);
        CountyTreasuryAccount account = service.addTreasuryAccount("Cluj", "TVA", "RO49...", "TVA");

        asTenant(TENANT_B);
        // RLS hides the row → service sees it as not found
        assertThatThrownBy(() -> service.deleteTreasuryAccount(account.getId()))
                .isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 2: Run it (expect skip without Docker)**

Run: `cd backend && mvn -B -Dtest=SettingsServiceIT test`
Expected: `BUILD SUCCESS` with test skipped (Docker absent). If Docker is present it should pass with 10 tests.

- [ ] **Step 3: Full test suite**

Run: `cd backend && mvn -B test`
Expected: `BUILD SUCCESS`, 6 unit tests pass, IT classes skip.

- [ ] **Step 4: Commit**
```bash
git add backend/src/test/java/ro/myfinance/settings/SettingsServiceIT.java
git commit -m "test(settings): SettingsServiceIT — VAT rate, treasury CRUD, cross-tenant isolation"
```

---

## Task 6: Frontend domain lists + i18n

**Files:**
- Create: `frontend/src/domain/counties.ts`
- Create: `frontend/src/domain/taxTypes.ts`
- Create: `frontend/src/domain/company.ts`
- Modify: `frontend/src/i18n.ts`

- [ ] **Step 1: Create `frontend/src/domain/counties.ts`**
```ts
/** The 42 Romanian counties (41 județe + București). */
export const ROMANIAN_COUNTIES = [
  "Alba", "Arad", "Argeș", "Bacău", "Bihor", "Bistrița-Năsăud", "Botoșani",
  "Brașov", "Brăila", "Buzău", "Caraș-Severin", "Călărași", "Cluj", "Constanța",
  "Covasna", "Dâmbovița", "Dolj", "Galați", "Giurgiu", "Gorj", "Harghita",
  "Hunedoara", "Ialomița", "Iași", "Ilfov", "Maramureș", "Mehedinți", "Mureș",
  "Neamț", "Olt", "Prahova", "Satu Mare", "Sălaj", "Sibiu", "Suceava",
  "Teleorman", "Timiș", "Tulcea", "Vaslui", "Vâlcea", "Vrancea", "București",
] as const;

export type RomanianCounty = (typeof ROMANIAN_COUNTIES)[number];
```

- [ ] **Step 2: Create `frontend/src/domain/taxTypes.ts`**
```ts
/** Canonical tax-type codes for county treasury accounts. Labels via i18n key `taxType.<code>`. */
export const TAX_TYPES = [
  "TVA",
  "IMPOZIT_PROFIT",
  "IMPOZIT_MICRO",
  "IMPOZIT_SALARII",
  "CAS",
  "CASS",
  "CAM",
  "IMPOZIT_DIVIDENDE",
] as const;

export type TaxType = (typeof TAX_TYPES)[number];

export const taxTypeKey = (code: string) => `taxType.${code}`;
```

- [ ] **Step 3: Create `frontend/src/domain/company.ts`**
```ts
/** Entity types for a Romanian company. */
export const ENTITY_TYPES = ["SRL", "SA", "PFA", "ONG"] as const;
export type EntityType = (typeof ENTITY_TYPES)[number];

/** VAT period codes stored in the DB. Labels via i18n key `vatPeriod.<code>`. */
export const VAT_PERIODS = ["MONTHLY", "QUARTERLY", "SEMIANNUAL", "ANNUAL"] as const;
export type VatPeriod = (typeof VAT_PERIODS)[number];

export const vatPeriodKey = (code: string) => `vatPeriod.${code}`;
```

- [ ] **Step 4: Add all new keys to `frontend/src/i18n.ts`**

In the `ro.translation` object, add (after the existing `company.invite` key):
```ts
      "company.entityType": "Tip entitate",
      "company.fiscalResidence": "Reședință fiscală",
      "vatPeriod.MONTHLY": "1 Lună",
      "vatPeriod.QUARTERLY": "3 Luni",
      "vatPeriod.SEMIANNUAL": "6 Luni",
      "vatPeriod.ANNUAL": "1 An",
      "taxType.TVA": "TVA",
      "taxType.IMPOZIT_PROFIT": "Impozit pe profit",
      "taxType.IMPOZIT_MICRO": "Impozit micro",
      "taxType.IMPOZIT_SALARII": "Impozit pe salarii",
      "taxType.CAS": "CAS",
      "taxType.CASS": "CASS",
      "taxType.CAM": "CAM",
      "taxType.IMPOZIT_DIVIDENDE": "Impozit dividende",
      "nav.settings": "Setări generale",
      "settings.vat": "Cotă TVA",
      "settings.vatRate": "Cotă TVA (%)",
      "settings.treasury": "Conturi trezorerie pe județ",
      "settings.county": "Județ",
      "settings.taxType": "Tip taxă",
      "common.save": "Salvează",
```

In the `en.translation` object, add the same keys in English (after `company.invite`):
```ts
      "company.entityType": "Entity type",
      "company.fiscalResidence": "Fiscal residence",
      "vatPeriod.MONTHLY": "Monthly",
      "vatPeriod.QUARTERLY": "Quarterly",
      "vatPeriod.SEMIANNUAL": "Semiannual",
      "vatPeriod.ANNUAL": "Annual",
      "taxType.TVA": "VAT",
      "taxType.IMPOZIT_PROFIT": "Corporate tax",
      "taxType.IMPOZIT_MICRO": "Micro tax",
      "taxType.IMPOZIT_SALARII": "Salary tax",
      "taxType.CAS": "CAS",
      "taxType.CASS": "CASS",
      "taxType.CAM": "CAM",
      "taxType.IMPOZIT_DIVIDENDE": "Dividend tax",
      "nav.settings": "General Settings",
      "settings.vat": "VAT rate",
      "settings.vatRate": "VAT rate (%)",
      "settings.treasury": "County treasury accounts",
      "settings.county": "County",
      "settings.taxType": "Tax type",
      "common.save": "Save",
```

- [ ] **Step 5: Type-check**

Run: `cd frontend && npx tsc -b`
Expected: no errors.

- [ ] **Step 6: Commit**
```bash
git add frontend/src/domain/ frontend/src/i18n.ts
git commit -m "feat(fe): domain lists (counties, tax types, entity/VAT-period codes) + i18n keys"
```

---

## Task 7: Company form dropdowns + remove TreasurySection

**Files:**
- Modify: `frontend/src/api/companies.ts`
- Modify: `frontend/src/components/AddCompanyModal.tsx`
- Modify: `frontend/src/pages/CompanyDetail.tsx`

- [ ] **Step 1: Remove treasury from `frontend/src/api/companies.ts`**

Replace the file entirely:
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

export const companiesApi = {
  list: () => api<Company[]>("/api/v1/companies"),
  get: (id: string) => api<Company>(`/api/v1/companies/${id}`),
  create: (input: CreateCompanyInput) =>
    api<Company>("/api/v1/companies", { method: "POST", body: JSON.stringify(input) }),
  update: (id: string, input: Partial<CreateCompanyInput>) =>
    api<Company>(`/api/v1/companies/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  setStatus: (id: string, status: "ACTIVE" | "INACTIVE") =>
    api<Company>(`/api/v1/companies/${id}/status`, {
      method: "PATCH",
      body: JSON.stringify({ status }),
    }),
};
```

- [ ] **Step 2: Replace `frontend/src/components/AddCompanyModal.tsx`** — entity type, VAT period, fiscal residence become dropdowns:
```tsx
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { companiesApi, type CreateCompanyInput } from "../api/companies";
import { ApiError } from "../lib/apiClient";
import { VAT_STATUSES, vatStatusKey } from "../domain/vat";
import { ENTITY_TYPES } from "../domain/company";
import { VAT_PERIODS, vatPeriodKey } from "../domain/company";
import { ROMANIAN_COUNTIES } from "../domain/counties";
import { Field } from "./Field";

export function AddCompanyModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
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

  const set =
    (k: keyof CreateCompanyInput) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
      setForm((f) => ({ ...f, [k]: e.target.value }));

  return (
    <div style={overlay} onClick={onClose}>
      <form
        className="card"
        style={{ width: 480, maxHeight: "90vh", overflowY: "auto" }}
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          setError(null);
          mutation.mutate();
        }}
      >
        <h2 style={{ marginTop: 0 }}>Add company</h2>
        <Field label="Legal name *">
          <input required value={form.legalName} onChange={set("legalName")} />
        </Field>
        <Field label="CUI *">
          <input required value={form.cui} onChange={set("cui")} />
        </Field>
        <Field label={t("company.entityType")}>
          <select value={form.entityType ?? ""} onChange={set("entityType")}>
            <option value="">—</option>
            {ENTITY_TYPES.map((v) => <option key={v} value={v}>{v}</option>)}
          </select>
        </Field>
        <Field label={t("company.fiscalResidence")}>
          <select value={form.locality ?? ""} onChange={set("locality")}>
            <option value="">—</option>
            {ROMANIAN_COUNTIES.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
        </Field>
        <Field label={t("company.vatStatus")}>
          <select value={form.vatStatus ?? ""} onChange={set("vatStatus")}>
            <option value="">—</option>
            {VAT_STATUSES.map((v) => (
              <option key={v} value={v}>{t(vatStatusKey(v))}</option>
            ))}
          </select>
        </Field>
        <Field label={t("company.vatPeriod")}>
          <select value={form.vatPeriod ?? ""} onChange={set("vatPeriod")}>
            <option value="">—</option>
            {VAT_PERIODS.map((v) => (
              <option key={v} value={v}>{t(vatPeriodKey(v))}</option>
            ))}
          </select>
        </Field>
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

const overlay: React.CSSProperties = {
  position: "fixed",
  inset: 0,
  background: "rgba(15,23,42,0.4)",
  display: "grid",
  placeItems: "center",
  zIndex: 50,
};
```

- [ ] **Step 3: Replace `frontend/src/pages/CompanyDetail.tsx`** — remove TreasurySection, update edit form dropdowns:
```tsx
import { useState } from "react";
import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { companiesApi, type Company } from "../api/companies";
import { representativesApi } from "../api/representatives";
import { ApiError } from "../lib/apiClient";
import { VAT_STATUSES, vatStatusKey } from "../domain/vat";
import { ENTITY_TYPES, VAT_PERIODS, vatPeriodKey } from "../domain/company";
import { ROMANIAN_COUNTIES } from "../domain/counties";
import { Field } from "../components/Field";

/** Client company detail: general info (view/edit), representatives. */
export function CompanyDetail() {
  const { id = "" } = useParams();
  const company = useQuery({ queryKey: ["company", id], queryFn: () => companiesApi.get(id) });

  if (company.isLoading) return <div className="card">Loading…</div>;
  if (company.error)
    return (
      <div className="card">
        <p style={{ color: "#dc2626" }}>
          {company.error instanceof ApiError ? company.error.message : "Failed to load company"}
        </p>
      </div>
    );

  const c = company.data!;
  return (
    <div style={{ display: "grid", gap: 16 }}>
      <GeneralInfoSection company={c} />
      <RepresentativesSection companyId={id} />
    </div>
  );
}

function GeneralInfoSection({ company }: { company: Company }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState(() => toForm(company));

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ["company", company.id] });
    void qc.invalidateQueries({ queryKey: ["companies"] });
  };

  const save = useMutation({
    mutationFn: () => companiesApi.update(company.id, form),
    onSuccess: () => { invalidate(); setEditing(false); },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Failed to save"),
  });

  const toggleStatus = useMutation({
    mutationFn: () =>
      companiesApi.setStatus(company.id, company.status === "ACTIVE" ? "INACTIVE" : "ACTIVE"),
    onSuccess: invalidate,
  });

  if (!editing) {
    return (
      <div className="card">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h1 style={{ marginTop: 0 }}>{company.legalName}</h1>
          <div style={{ display: "flex", gap: 8 }}>
            <button onClick={() => { setForm(toForm(company)); setError(null); setEditing(true); }}>
              Edit
            </button>
            <button onClick={() => toggleStatus.mutate()} disabled={toggleStatus.isPending}>
              {company.status === "ACTIVE" ? "Deactivate" : "Activate"}
            </button>
          </div>
        </div>
        <dl style={grid}>
          <Row k="CUI" v={company.cui} />
          <Row k={t("company.entityType")} v={company.entityType ?? "—"} />
          <Row k={t("company.fiscalResidence")} v={company.locality ?? "—"} />
          <Row k={t("company.vatStatus")} v={company.vatStatus ? t(vatStatusKey(company.vatStatus), { defaultValue: company.vatStatus }) : "—"} />
          <Row k={t("company.vatPeriod")} v={company.vatPeriod ? t(vatPeriodKey(company.vatPeriod), { defaultValue: company.vatPeriod }) : "—"} />
          <Row k="Status" v={company.status} />
        </dl>
      </div>
    );
  }

  return (
    <div className="card">
      <h1 style={{ marginTop: 0 }}>Edit company</h1>
      <form onSubmit={(e) => { e.preventDefault(); setError(null); save.mutate(); }}>
        <Field label="Legal name *">
          <input required value={form.legalName} onChange={(e) => setForm({ ...form, legalName: e.target.value })} />
        </Field>
        <Field label="CUI">
          <input value={company.cui} disabled />
        </Field>
        <Field label={t("company.entityType")}>
          <select value={form.entityType} onChange={(e) => setForm({ ...form, entityType: e.target.value })}>
            <option value="">—</option>
            {ENTITY_TYPES.map((v) => <option key={v} value={v}>{v}</option>)}
          </select>
        </Field>
        <Field label={t("company.fiscalResidence")}>
          <select value={form.locality} onChange={(e) => setForm({ ...form, locality: e.target.value })}>
            <option value="">—</option>
            {ROMANIAN_COUNTIES.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
        </Field>
        <Field label={t("company.vatStatus")}>
          <select value={form.vatStatus} onChange={(e) => setForm({ ...form, vatStatus: e.target.value })}>
            <option value="">—</option>
            {VAT_STATUSES.map((v) => (
              <option key={v} value={v}>{t(vatStatusKey(v))}</option>
            ))}
          </select>
        </Field>
        <Field label={t("company.vatPeriod")}>
          <select value={form.vatPeriod} onChange={(e) => setForm({ ...form, vatPeriod: e.target.value })}>
            <option value="">—</option>
            {VAT_PERIODS.map((v) => (
              <option key={v} value={v}>{t(vatPeriodKey(v))}</option>
            ))}
          </select>
        </Field>
        {error && <p style={{ color: "#dc2626" }}>{error}</p>}
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
          <button type="button" onClick={() => setEditing(false)}>Cancel</button>
          <button className="primary" type="submit" disabled={save.isPending}>
            {save.isPending ? "Saving…" : t("common.save")}
          </button>
        </div>
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
          <li key={r.id}>
            {r.name ?? r.email} — {r.email}{" "}
            <span style={pill}>{r.status}</span>
          </li>
        ))}
        {reps.data?.length === 0 && (
          <li style={{ color: "var(--text-muted)" }}>No representatives yet</li>
        )}
      </ul>
      {error && <p style={{ color: "#dc2626" }}>{error}</p>}
      <form
        style={{ display: "flex", gap: 8, marginTop: 8 }}
        onSubmit={(e) => { e.preventDefault(); invite.mutate(); }}
      >
        <input type="email" placeholder="email" required value={form.email}
          onChange={(e) => setForm({ ...form, email: e.target.value })} />
        <input placeholder="name" value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })} />
        <button className="primary" type="submit" disabled={invite.isPending}>Invite</button>
      </form>
    </div>
  );
}

function toForm(c: Company) {
  return {
    legalName: c.legalName,
    entityType: c.entityType ?? "",
    locality: c.locality ?? "",
    vatStatus: c.vatStatus ?? "",
    vatPeriod: c.vatPeriod ?? "",
  };
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <>
      <dt style={{ color: "var(--text-muted)" }}>{k}</dt>
      <dd style={{ margin: 0 }}>{v}</dd>
    </>
  );
}

const grid: React.CSSProperties = { display: "grid", gridTemplateColumns: "160px 1fr", rowGap: 8 };
const pill: React.CSSProperties = { background: "var(--border)", borderRadius: 999, padding: "2px 8px", fontSize: 12 };
```

- [ ] **Step 4: Build**

Run: `cd frontend && npm run build`
Expected: build succeeds with no errors. If there's a TypeScript error about `vatPeriodKey` import path (`../domain/company`), verify the export name in `company.ts`.

- [ ] **Step 5: Commit**
```bash
git add frontend/src/api/companies.ts frontend/src/components/AddCompanyModal.tsx frontend/src/pages/CompanyDetail.tsx
git commit -m "feat(fe): company form dropdowns (entity type, VAT period, fiscal residence); remove TreasurySection"
```

---

## Task 8: Settings API client + Settings page

**Files:**
- Create: `frontend/src/api/settings.ts`
- Create: `frontend/src/pages/Settings.tsx`

- [ ] **Step 1: Create `frontend/src/api/settings.ts`**
```ts
import { api } from "../lib/apiClient";

export interface GeneralSettings {
  vatRate: number;
}

export interface CountyTreasuryAccount {
  id: string;
  county: string;
  taxType: string;
  iban: string;
  label: string | null;
}

export const settingsApi = {
  get: () => api<GeneralSettings>("/api/v1/settings"),
  updateVatRate: (vatRate: number) =>
    api<GeneralSettings>("/api/v1/settings", {
      method: "PUT",
      body: JSON.stringify({ vatRate }),
    }),
  listTreasury: () => api<CountyTreasuryAccount[]>("/api/v1/settings/treasury-accounts"),
  addTreasury: (input: { county: string; taxType: string; iban: string; label?: string }) =>
    api<CountyTreasuryAccount>("/api/v1/settings/treasury-accounts", {
      method: "POST",
      body: JSON.stringify(input),
    }),
  deleteTreasury: (id: string) =>
    api<void>(`/api/v1/settings/treasury-accounts/${id}`, { method: "DELETE" }),
};
```

- [ ] **Step 2: Create `frontend/src/pages/Settings.tsx`**
```tsx
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { settingsApi } from "../api/settings";
import { ApiError } from "../lib/apiClient";
import { ROMANIAN_COUNTIES } from "../domain/counties";
import { TAX_TYPES, taxTypeKey } from "../domain/taxTypes";
import { Field } from "../components/Field";

/** Tenant-level general settings: VAT rate + county treasury-account registry. TENANT_ADMIN only. */
export function Settings() {
  const { t } = useTranslation();
  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="card">
        <h1 style={{ marginTop: 0 }}>{t("nav.settings")}</h1>
      </div>
      <VatRateSection />
      <CountyTreasurySection />
    </div>
  );
}

function VatRateSection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({ queryKey: ["settings"], queryFn: settingsApi.get });
  const [editing, setEditing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const displayed = editing ?? String(data?.vatRate ?? "21");

  const save = useMutation({
    mutationFn: () => settingsApi.updateVatRate(parseFloat(displayed)),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["settings"] });
      setEditing(null);
      setError(null);
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Failed to save"),
  });

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("settings.vat")}</h2>
      {isLoading ? (
        <p>{t("common.loading")}</p>
      ) : (
        <form
          onSubmit={(e) => { e.preventDefault(); setError(null); save.mutate(); }}
          style={{ display: "flex", alignItems: "flex-end", gap: 12 }}
        >
          <Field label={t("settings.vatRate")}>
            <input
              type="number"
              min="0"
              max="100"
              step="0.01"
              required
              value={displayed}
              onChange={(e) => setEditing(e.target.value)}
              style={{ maxWidth: 100 }}
            />
          </Field>
          <button className="primary" type="submit" disabled={save.isPending} style={{ marginBottom: 10 }}>
            {save.isPending ? "Saving…" : t("common.save")}
          </button>
        </form>
      )}
      {error && <p style={{ color: "#dc2626" }}>{error}</p>}
    </div>
  );
}

function CountyTreasurySection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data: accounts = [], isLoading } = useQuery({
    queryKey: ["county-treasury"],
    queryFn: settingsApi.listTreasury,
  });
  const [form, setForm] = useState({ county: "", taxType: "", iban: "", label: "" });
  const [error, setError] = useState<string | null>(null);

  const add = useMutation({
    mutationFn: () => settingsApi.addTreasury(form),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["county-treasury"] });
      setForm({ county: "", taxType: "", iban: "", label: "" });
      setError(null);
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Failed to add account"),
  });

  const remove = useMutation({
    mutationFn: (id: string) => settingsApi.deleteTreasury(id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["county-treasury"] }),
  });

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("settings.treasury")}</h2>
      {isLoading ? (
        <p>{t("common.loading")}</p>
      ) : (
        <>
          <table style={{ width: "100%", borderCollapse: "collapse", marginBottom: 16 }}>
            <thead>
              <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
                <th style={{ padding: 8 }}>{t("settings.county")}</th>
                <th style={{ padding: 8 }}>{t("settings.taxType")}</th>
                <th style={{ padding: 8 }}>IBAN</th>
                <th style={{ padding: 8 }}>Label</th>
                <th style={{ padding: 8 }} />
              </tr>
            </thead>
            <tbody>
              {accounts.map((a) => (
                <tr key={a.id} style={{ borderTop: "1px solid var(--border)" }}>
                  <td style={{ padding: 8 }}>{a.county}</td>
                  <td style={{ padding: 8 }}>{t(taxTypeKey(a.taxType), { defaultValue: a.taxType })}</td>
                  <td style={{ padding: 8, fontFamily: "monospace" }}>{a.iban}</td>
                  <td style={{ padding: 8 }}>{a.label ?? "—"}</td>
                  <td style={{ padding: 8 }}>
                    <button
                      onClick={() => remove.mutate(a.id)}
                      disabled={remove.isPending}
                      style={{ color: "#dc2626", border: "none", background: "none", cursor: "pointer", padding: "0 4px" }}
                    >
                      ✕
                    </button>
                  </td>
                </tr>
              ))}
              {accounts.length === 0 && (
                <tr>
                  <td colSpan={5} style={{ padding: 8, color: "var(--text-muted)" }}>
                    No treasury accounts configured yet.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          {error && <p style={{ color: "#dc2626" }}>{error}</p>}
          <form
            style={{ display: "flex", gap: 8, flexWrap: "wrap" }}
            onSubmit={(e) => { e.preventDefault(); add.mutate(); }}
          >
            <select
              required
              value={form.county}
              onChange={(e) => setForm({ ...form, county: e.target.value })}
              style={{ flex: "1 1 150px" }}
            >
              <option value="">{t("settings.county")}…</option>
              {ROMANIAN_COUNTIES.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
            <select
              required
              value={form.taxType}
              onChange={(e) => setForm({ ...form, taxType: e.target.value })}
              style={{ flex: "1 1 150px" }}
            >
              <option value="">{t("settings.taxType")}…</option>
              {TAX_TYPES.map((tt) => (
                <option key={tt} value={tt}>{t(taxTypeKey(tt))}</option>
              ))}
            </select>
            <input
              required
              placeholder="IBAN"
              value={form.iban}
              onChange={(e) => setForm({ ...form, iban: e.target.value })}
              style={{ flex: "2 1 200px" }}
            />
            <input
              placeholder="Label"
              value={form.label}
              onChange={(e) => setForm({ ...form, label: e.target.value })}
              style={{ flex: "1 1 100px" }}
            />
            <button className="primary" type="submit" disabled={add.isPending}>
              {add.isPending ? "Adding…" : "Add"}
            </button>
          </form>
        </>
      )}
    </div>
  );
}
```

- [ ] **Step 3: Type-check and build**

Run: `cd frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 4: Commit**
```bash
git add frontend/src/api/settings.ts frontend/src/pages/Settings.tsx
git commit -m "feat(fe): Settings page — VAT rate + county treasury accounts CRUD"
```

---

## Task 9: Nav + route + final verification

**Files:**
- Modify: `frontend/src/components/FirmLayout.tsx`
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Add Settings to nav in `frontend/src/components/FirmLayout.tsx`**

In `FirmLayout.tsx`, the sidebar renders `NAV` entries (visible to all roles) and then a SUPER_ADMIN-only entry. Settings is TENANT_ADMIN-only. Add it inside the nav block after the general entries, similar to the SUPER_ADMIN guard:

After the `{NAV.map(...)}` block and the `{role === "SUPER_ADMIN" && ...}` line, add:
```tsx
{role === "TENANT_ADMIN" && (
  <NavLink to="/settings">{t("nav.settings")}</NavLink>
)}
```

The full nav block in the sidebar should look like:
```tsx
        <nav>
          {NAV.map((item) => (
            <NavLink key={item.to} to={item.to}>
              {t(item.key)}
            </NavLink>
          ))}
          {role === "SUPER_ADMIN" && <NavLink to="/admin/tenants">{t("nav.tenants")}</NavLink>}
          {role === "TENANT_ADMIN" && <NavLink to="/settings">{t("nav.settings")}</NavLink>}
        </nav>
```

- [ ] **Step 2: Add `/settings` route in `frontend/src/App.tsx`**

Add the import near the other page imports:
```tsx
import { Settings } from "./pages/Settings";
```

Add the route inside the TENANT_ADMIN block (the block that has `RequireRole allow={["TENANT_ADMIN", "EMPLOYEE", "SUPER_ADMIN"]}`), after the `/tasks` route:
```tsx
          <Route path="/settings" element={<Settings />} />
```

But the Settings page should only be reachable by TENANT_ADMIN. Since it's inside the shared `RequireRole allow={["TENANT_ADMIN","EMPLOYEE","SUPER_ADMIN"]}` block, Employees would also reach it. Move Settings to its own guard block (add after the firm-app block):
```tsx
      {/* Settings — TENANT_ADMIN only */}
      <Route element={<RequireRole allow={["TENANT_ADMIN"]} />}>
        <Route element={<FirmLayout />}>
          <Route path="/settings" element={<Settings />} />
        </Route>
      </Route>
```

- [ ] **Step 3: Final lint + build**

Run: `cd frontend && npm run lint && npm run build`
Expected: both succeed with zero errors.

- [ ] **Step 4: Full backend test suite**

Run: `cd backend && mvn -B test`
Expected: `BUILD SUCCESS`, all unit tests pass, IT classes skip (no Docker).

- [ ] **Step 5: Commit**
```bash
git add frontend/src/components/FirmLayout.tsx frontend/src/App.tsx
git commit -m "feat(fe): General Settings nav + route (TENANT_ADMIN only)"
```

---

## Self-review

**Spec coverage:**
- ✅ Company: entity type dropdown (SRL/SA/PFA/ONG) — Task 7
- ✅ VAT period: MONTHLY/QUARTERLY/SEMIANNUAL/ANNUAL with RO labels — Tasks 6, 7
- ✅ Fiscal residence: 42 Romanian county dropdown — Tasks 6, 7
- ✅ General Settings module: VAT rate (default 21%) — Tasks 3, 4, 8
- ✅ County treasury accounts (county + tax type) — Tasks 3, 4, 8
- ✅ Drop per-company treasury — Tasks 1, 2, 7
- ✅ RLS + cross-tenant isolation for new tables — Task 1 (migration), Task 5 (IT)
- ✅ TENANT_ADMIN-only access for settings — Task 4 (controller), Task 9 (route guard)
- ✅ Seed updated — Task 1

**Type consistency check:**
- `SettingsService.addTreasuryAccount(county, taxType, iban, label)` → matches `SettingsController` → matches `settingsApi.addTreasury` → matches `Settings.tsx` form submission. ✅
- `companiesApi` no longer has `listTreasury`/`addTreasury`; `companies.ts` type removed. ✅
- `vatPeriodKey` exported from `domain/company.ts`, imported in `AddCompanyModal` and `CompanyDetail`. ✅
