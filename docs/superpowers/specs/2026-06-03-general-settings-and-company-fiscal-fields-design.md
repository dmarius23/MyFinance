# General Settings & Company Fiscal Fields — Design Spec

**Date:** 2026-06-03
**Modules:** MOD-03 (Company), new **Settings** module (tenant-level config)
**Status:** Approved for planning

## 1. Goal & scope

1. Constrain & translate company fiscal fields on the add/edit forms (entity type, VAT period, fiscal residence/county).
2. Add a **General Settings** module: a tenant-level VAT rate and a per-county/tax-type treasury-account registry, replacing the per-company treasury accounts.

**Out of scope:** using the VAT rate in any calculation (it's a stored default for now); county-based auto-resolution of a company's treasury accounts for payment emails (a MOD-07 concern); validating county/tax-type values server-side beyond storing strings.

## 2. Company form changes (frontend-only; no DB schema change)

The `company` columns already exist; we constrain inputs and translate labels. Values stay language-independent.

| Field | Label RO / EN | Control | Stored values |
|---|---|---|---|
| entity_type | Tip entitate / Entity type | dropdown | `SRL`, `SA`, `PFA`, `ONG` |
| vat_period | Perioadă TVA / VAT period | dropdown | `MONTHLY`, `QUARTERLY`, `SEMIANNUAL`, `ANNUAL` → shown as **1 LUNĂ / 3 LUNI / 6 LUNI / 1 AN** (EN: Monthly/Quarterly/Semiannual/Annual) |
| locality | Rezidență fiscală / Fiscal residence | dropdown | one of the 42 Romanian counties (name, e.g. `Cluj`, `București`) |

- The DB column stays named `locality` (internal); only the UI label/control change. (A real column rename is deferred — flagged, not done.)
- `vat_status` dropdown (`VAT_PAYER`/`NON_VAT_PAYER`) already shipped.
- The per-company **Treasury accounts** section is **removed** from the company detail page (superseded by §3).

**Counties (42):** Alba, Arad, Argeș, Bacău, Bihor, Bistrița-Năsăud, Botoșani, Brașov, Brăila, Buzău, Caraș-Severin, Călărași, Cluj, Constanța, Covasna, Dâmbovița, Dolj, Galați, Giurgiu, Gorj, Harghita, Hunedoara, Ialomița, Iași, Ilfov, Maramureș, Mehedinți, Mureș, Neamț, Olt, Prahova, Satu Mare, Sălaj, Sibiu, Suceava, Teleorman, Timiș, Tulcea, Vaslui, Vâlcea, Vrancea, București.

## 3. Settings module (`ro.myfinance.settings`)

### 3.1 Data model (Flyway `V3__general_settings.sql`)

```sql
-- one row per tenant; created lazily on first read
general_settings(
  tenant_id  uuid PRIMARY KEY REFERENCES tenant(id),
  vat_rate   numeric(5,2) NOT NULL DEFAULT 21.00,
  updated_at timestamptz  NOT NULL DEFAULT now()
)

county_treasury_account(
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id  uuid NOT NULL REFERENCES tenant(id),
  county     text NOT NULL,
  tax_type   text NOT NULL,
  iban       text NOT NULL,
  label      text,
  UNIQUE (tenant_id, county, tax_type)
)

DROP TABLE treasury_account;   -- per-company treasury, superseded
```
- Both new tables get `ENABLE`/`FORCE ROW LEVEL SECURITY` + a `tenant_isolation` policy `USING/WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true),'')::uuid)`, matching V1's pattern.
- Table privileges for `myfinance_app` come from V1's `ALTER DEFAULT PRIVILEGES` (applies to new tables created by the admin role); the migration still explicitly enables RLS + policy per table.
- Update `V1000__dev_seed.sql`: remove the `treasury_account` insert; optionally seed a `general_settings` row + a couple of `county_treasury_account` rows for the demo tenant.

### 3.2 Tax types (defined list, RO labels)
`TVA`, `IMPOZIT_PROFIT`, `IMPOZIT_MICRO`, `IMPOZIT_SALARII`, `CAS`, `CASS`, `CAM`, `IMPOZIT_DIVIDENDE` → RO labels (TVA, Impozit pe profit, Impozit micro, Impozit pe salarii, CAS, CASS, CAM, Impozit dividende).

### 3.3 Backend

```
ro.myfinance.settings
 ├─ domain/        GeneralSettings, CountyTreasuryAccount
 ├─ application/   SettingsService
 └─ adapter/
     ├─ persistence/  GeneralSettingsRepository, CountyTreasuryAccountRepository
     └─ web/          SettingsController, SettingsDtos
```
- `SettingsService` (tenant-scoped via `TenantContext`):
  - `getSettings()` — returns the tenant's `general_settings`, creating the default row (vat 21.00) if absent.
  - `updateVatRate(BigDecimal rate)` — validates `0 <= rate <= 100`.
  - `listTreasuryAccounts()`, `addTreasuryAccount(county, taxType, iban, label)` (409 on duplicate (county,taxType)), `deleteTreasuryAccount(id)` (404 if not in tenant).
- `SettingsController` `@PreAuthorize("hasRole('TENANT_ADMIN')")`:
  - `GET /api/v1/settings` → `{ vatRate }`
  - `PUT /api/v1/settings` `{ vatRate }`
  - `GET /api/v1/settings/treasury-accounts` → list
  - `POST /api/v1/settings/treasury-accounts` `{ county, taxType, iban, label }`
  - `DELETE /api/v1/settings/treasury-accounts/{id}`

### 3.4 Remove per-company treasury
Delete `company/domain/TreasuryAccount.java`, `company/adapter/persistence/TreasuryAccountRepository.java`, the treasury methods on `CompanyService`, the treasury endpoints + DTOs on `CompanyController`/`CompanyDtos`, and the frontend `TreasurySection` + `companiesApi.listTreasury/addTreasury`.

## 4. Frontend

- `src/domain/counties.ts` — the 42 counties.
- `src/domain/taxTypes.ts` — tax-type codes + i18n keys.
- `src/domain/company.ts` (or extend `vat.ts`) — entity types + VAT period codes + i18n keys.
- Company add/edit forms: entity type, VAT period, fiscal-residence (county) become dropdowns; remove the treasury section from `CompanyDetail`.
- `src/api/settings.ts` — typed client for the settings + treasury endpoints.
- `src/pages/Settings.tsx` — VAT-rate field (default 21%) + county treasury table (add: county dropdown + tax-type dropdown + IBAN + label; delete per row).
- Nav: add **"Setări generale / General Settings"** entry (shown to TENANT_ADMIN) + route `/settings` (guarded `RequireRole allow={["TENANT_ADMIN"]}`).
- i18n: labels for the new fields, the dropdown option labels, and the settings page.

## 5. Testing

- Backend (Testcontainers, skip without Docker): `SettingsServiceIT` — lazy default settings + vat update; treasury add/list/delete; duplicate (county,taxType) → conflict; cross-tenant isolation (tenant B can't see/delete tenant A's settings/treasury). Update `CompanyServiceIT` to remove the deleted treasury test.
- Frontend: `npm run lint` + `npm run build` green.

## 6. Build order

1. Backend migration `V3` (create settings tables, drop treasury_account) + seed update.
2. Remove per-company treasury code (company module + tests).
3. Settings module: entities, repos, service, controller, DTOs (+ `SettingsServiceIT`).
4. Frontend domain lists (counties, tax types, entity types, VAT periods) + i18n.
5. Frontend company forms: dropdowns + relabels; remove treasury section.
6. Frontend Settings page + API + nav/route.
7. Verify (backend tests, frontend lint+build).
