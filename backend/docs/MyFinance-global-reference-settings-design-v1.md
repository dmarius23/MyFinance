# MyFinance — Global reference settings (tax rates + treasury accounts) — design & implementation brief v1

**Status:** Approved, ready to implement · **Owner:** TBD
**Purpose:** self-contained brief for a fresh session — implement without re-exploring.

## 1. Problem

Tax **rates** (VAT / micro / profit) and **treasury IBANs** (per fiscal residence/county) are **national
Romanian reference data — identical for every tenant**, but today they are stored **per-tenant** (each firm
re-enters them; they can drift). `sender_email` is genuinely per-tenant and stays so.

## 2. Decisions (locked with the user)

- **Global-only** — one source of truth for rates + treasury, maintained by **SUPER_ADMIN**; tenants are
  **read-only** (no per-tenant overrides).
- **Effective-dated** — rates/accounts are date-versioned; a computation for a given period uses the value
  that was valid **for that period** (e.g. VAT 19% before, 21% from its change date).
- `sender_email` (and future firm branding) **stays per-tenant**.
- Global tables have **no `tenant_id` and no RLS** (they hold only public reference data — no cross-tenant
  leak risk). Writes gated at the app layer by `@PreAuthorize("hasRole('SUPER_ADMIN')")`; the `myfinance_app`
  role gets full grants (a shared DB role can't distinguish SUPER_ADMIN — authz is app-layer, as elsewhere).
- **Note vs golden rule #1:** the "every tenant table has tenant_id + RLS + a cross-tenant isolation test"
  rule does **not** apply here precisely because these tables are intentionally global/public. Call this out
  in the migration comment and the PR so it isn't flagged as a violation. (Still add a test that a tenant
  staff request can READ but a non-SUPER_ADMIN cannot WRITE.)

## 3. Current state (files — verified)

Tables (all currently **per-tenant, RLS**):
- `general_settings` — PK `tenant_id`; cols `vat_rate, micro_rate, profit_rate, sender_email, updated_at`.
  Migrations: `V3__general_settings.sql`, `V13__settings_tax_rates.sql`, `V21__general_settings_sender_email.sql`.
  Entity: `backend/.../settings/domain/GeneralSettings.java`.
- `residence_treasury` — cols `id, tenant_id, residence, iban_cam, iban_impozite, iban_cass, iban_cas, iban_tva`.
  Migrations: `V14__residence_treasury_account.sql`, `V15__residence_treasury_columns.sql`.
  Entity: `backend/.../settings/domain/ResidenceTreasuryAccount.java`.

Application / adapters:
- `backend/.../settings/application/SettingsService.java` — `getSettings()`, `updateRates(vat,micro,profit,senderEmail)`,
  `senderEmail()`, `listTreasuryAccounts()`, `addTreasuryAccount(...)`, `updateTreasuryAccount(...)`, `deleteTreasuryAccount(id)`.
- `backend/.../settings/adapter/persistence/GeneralSettingsRepository.java`, `ResidenceTreasuryAccountRepository.java`
  (`findByResidence`, `existsByResidence`).
- `backend/.../settings/adapter/web/SettingsController.java` + `SettingsDtos.java` (tenant, `TENANT_ADMIN`).

Consumers to re-wire (grep to confirm the exact call sites):
- **Rates:** `grep -rn "getVatRate\|getMicroRate\|getProfitRate" backend/src` — the current rate consumers are
  thin (mostly the Settings screen display; confirm whether `taxpayments` computation actually reads them —
  much of the tax total comes from the ANAF declaration XML, not these rates). Wire whatever reads them to the
  new period-aware resolver.
- **Treasury IBANs:** `taxpayments/application/TaxPaymentService.java` (`resolveIbans` → `treasury.findByResidence(locality)`),
  and referenced from `PaymentCalculator.java`, `PaymentEmailBuilder.java`, `portal/application/PortalService.java`.
  These must resolve by **(residence, period)** via the new global service.

Frontend:
- `frontend/src/pages/Settings.tsx` — `VatRateSection` (rates + senderEmail) + `TreasurySection` (IBAN CRUD).
- `frontend/src/api/settings.ts` — `settingsApi.get/updateRates/listTreasury/addTreasury/updateTreasury/deleteTreasury`.
- i18n keys `settings.*` in `frontend/src/i18n.ts` (RO + EN — keep parity).
- SUPER_ADMIN area exists: `App.tsx` route `"/admin/tenants"` under `RequireRole allow={["SUPER_ADMIN"]}` (placeholder).

**Latest migration is V34 → next is V35.**

## 4. Target schema (global, no tenant_id, no RLS)

```sql
CREATE TABLE platform_tax_rate (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  category   text NOT NULL,            -- VAT | MICRO | PROFIT
  rate       numeric(6,2) NOT NULL,
  valid_from date NOT NULL,            -- effective-dated: greatest valid_from <= period wins
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (category, valid_from)
);
CREATE TABLE platform_treasury_account (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  residence     text NOT NULL,
  iban_cam text, iban_impozite text, iban_cass text, iban_cas text, iban_tva text,
  valid_from    date NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (residence, valid_from)
);
-- grants to myfinance_app (SELECT + write; write is app-gated to SUPER_ADMIN). NO RLS.
```

**Resolution rule (both):** for category/residence + target period `P`, pick the row with the **greatest
`valid_from ≤ P`**. No `valid_to` — the next-higher `valid_from` implicitly supersedes. If none `≤ P`, fall
back to the earliest row (or return absent + let the caller warn). Implement as `findTopBy…AndValidFromLessThanEqualOrderByValidFromDesc`.

## 5. Migration / data reconciliation (V35)

1. Create the two global tables (+ grants, no RLS).
2. **Seed `platform_tax_rate`** with the current national values at an early `valid_from` (e.g. `2020-01-01`)
   so all existing periods resolve: VAT 21, MICRO 3, PROFIT 16 (or read one tenant's `general_settings`).
   **Do NOT invent historical transitions** — leave real history (e.g. VAT 19→21) for the super-admin to add.
3. **Seed `platform_treasury_account`** from the **distinct residences** across all tenants' `residence_treasury`
   (`SELECT DISTINCT ON (residence) …`), `valid_from = 2020-01-01`.
4. Drop the rate columns from `general_settings` (`vat_rate, micro_rate, profit_rate`) — it keeps
   `tenant_id, sender_email, updated_at` (rename entity intent to "tenant settings" but keep table name to
   avoid churn). Drop the per-tenant `residence_treasury` table **after** seeding.

## 6. Backend changes

- New module home: put globals under `settings` (or a new `platform` sub-package). Entities
  `PlatformTaxRate`, `PlatformTreasuryAccount`; repositories with the effective-dated finders.
- `PlatformRatesService.rateFor(Category, LocalDate period)` and
  `PlatformTreasuryService.accountFor(String residence, LocalDate period)` — period-aware resolvers.
- Re-wire treasury IBAN resolution (`TaxPaymentService.resolveIbans`, `PaymentCalculator`, `PortalService`)
  to the global service **passing the document's period**. Same for any rate consumer.
- `SettingsService` slims to `sender_email` only (drop rate + treasury methods, or keep read-only accessors).
- `PlatformSettingsController` (`@PreAuthorize("hasRole('SUPER_ADMIN')")`): CRUD for rates + treasury with
  effective dates + list/history. Read endpoints may be open to any authenticated staff (tenants display them
  read-only).

## 7. Frontend changes

- Tenant **Settings** page: make rates + treasury **read-only** (display the effective values), keep the
  `sender_email` editor. Remove the write mutations for rates/treasury from `settings.ts`.
- New **SUPER_ADMIN admin screen** (add a route under the existing `RequireRole ["SUPER_ADMIN"]` block, e.g.
  `/admin/reference`): manage global rates + treasury accounts with `valid_from` + history (add/edit/delete).
- Keep RO/EN i18n parity (verify with a quick node script counting `"..."` keys per block).

## 8. Phased plan

1. **Globals + resolvers + V35 migration + re-wire treasury/rate reads (period-aware).** Ship + verify.
2. **SUPER_ADMIN CRUD API + admin screen.**
3. **Slim the tenant Settings page** (read-only rates/treasury); drop old per-tenant columns/table.

## 9. Repo conventions & verification (reuse from prior sessions)

- Java 21 / Spring Boot 3, hexagonal (`domain`/`application`/`adapter{web,persistence,external}`), Flyway.
- Backend build: `cd backend && mvn -q compile` / `mvn -o test -Dtest=…`. Restart the running app with
  `pkill -f spring-boot:run; nohup bash run-local.sh > /tmp/be.log 2>&1 &` then poll `:8080/actuator/health`.
- Dev DB: `psql -d myfinance -U marius -h localhost`. Docker IS available (Testcontainers RLS tests run).
- Frontend: `cd frontend && npx tsc --noEmit && npx eslint <files>`; dev server on `:5173`
  (browser automation via Chrome MCP is flaky — dev service worker can freeze the renderer; rely on
  typecheck/lint + patterns, verify visually if it cooperates).
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`; push to `master`.
- Secrets only in gitignored `backend/run-local.sh`; never commit/print.

## 10. Session context (what shipped before this, for orientation)

Recent features already on `master`: rep in-app notifications; Web Push (VAPID, `push_subscription`);
dashboard cell deep-links + representative column; inline-first module lists (removed Actions columns);
**single per-tenant Google Drive connection** (read+write) driving both ingestion and the upload mirror
(`DriveDocLayout` type↔folder, layout `company/YYYY/MM/type`, `DocumentMirrorListener`); Sync+Upload in the
file modals; ingestion dedupe scoped to (connection, company, period) with ledger upsert (V34). Migrations
through **V34**.
