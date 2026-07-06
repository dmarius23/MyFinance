# Companies & Representatives — Design Spec

**Date:** 2026-06-03
**Modules:** MOD-03 (Client Company Management), MOD-02 (Users & Access)
**Status:** Approved for planning

## 1. Goal & scope

Deliver the create/view experience for **client companies** and the **representatives** linked to
those companies, plus **treasury accounts** on the company detail page.

**In scope**
- Companies: list, add, edit, view detail, activate/inactivate.
- Treasury accounts: list + add, on the company detail page.
- Representatives: list per company, invite by email (Supabase Auth), view status.

**Deferred (out of scope here)**
- Tax-profile obligations matrix (FR-017) and Romanian deadline pre-fill/override (FR-020b).
- Company detail cards for Statements / Taxes / Payroll / Reports (their own modules MOD-04/06/07/08).
- Editing/removing a representative link, resend-invite (can be a fast follow).
- Frontend unit-test runner (none configured; not added here).

## 2. Requirements covered

- **FR-015** create/edit, mark active/inactive.
- **FR-016** core fiscal fields: legal name, entity type, CUI/CIF, reg-no, address/locality,
  VAT status (payer + monthly/quarterly), tax regime, responsible employee.
- **FR-018** treasury accounts per company, keyed by tax type + locality.
- **FR-010** invite representatives by email; invitation-only.
- **FR-011** a company has many representatives; each representative is linked to exactly one company.
- **FR-012** representatives are strictly scoped to their linked company (server-enforced via RLS + role).

Business rules honored: CUI unique per tenant; inactive companies retain data; one-company-per-rep.

## 3. Backend design

### 3.1 Model corrections (current code is wrong on two points)

1. **One company per representative (FR-011).** `representative_link` currently has a composite
   primary key `(user_id, company_id)`, which would let one rep link to multiple companies. Change
   the primary key to **`user_id`** (a rep belongs to exactly one company; a company still has many
   reps via many link rows). Delivered as Flyway migration `V2__representative_link_one_company.sql`
   (drop the composite PK, add PK on `user_id`).

2. **`app_user.id` is the Supabase auth user id.** Every `app_user` row corresponds to a Supabase
   auth user, and the JWT `sub` is that id; `TenantContextFilter` reads it. `AppUser` currently
   auto-generates its id (`@GeneratedValue(strategy = UUID)`), which would diverge from the auth id.
   Change `AppUser` to an **assigned** id (no `@GeneratedValue`); the constructor/factory takes the
   id. Seed data and tests already assign explicit ids, so no data migration is needed.

### 3.2 Representative invite — port + adapters

A hexagonal external port keeps Supabase behind an interface and lets the flow run/test without creds.
The port interface lives in `mod02_access/application`; both adapters live in
`mod02_access/adapter/external`.

```
RepresentativeInviter (port, mod02_access/application)

    InvitedUser invite(String email, InviteClaims claims)
    record InviteClaims(UUID tenantId, Role role, UUID companyId)
    record InvitedUser(UUID externalUserId)
```

- **`SupabaseRepresentativeInviter`** (real adapter) — uses Spring `RestClient` to call GoTrue admin
  REST on `SUPABASE_URL` with the `SUPABASE_SERVICE_ROLE_KEY` as bearer + apikey:
  1. `POST /auth/v1/invite` `{ email }` → creates the user and sends the invite email; returns the id.
  2. `PUT /auth/v1/admin/users/{id}` `{ app_metadata: { tenant_id, role, company_id } }` → sets the
     claims the access-token hook will lift into the JWT.
  Active only when `SUPABASE_SERVICE_ROLE_KEY` is set (`@ConditionalOnProperty`).
- **`LoggingRepresentativeInviter`** (fallback) — `@ConditionalOnMissingBean(RepresentativeInviter.class)`;
  mints a random UUID and logs the intended invite. Lets the full flow work locally and in tests
  today; goes away the moment real creds are configured.

Secrets via env only (`SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`); never committed. Added to
`.env.example` and `application.yml` with empty defaults.

### 3.3 Invite use-case (MOD-02 `AccessService` / new `RepresentativeService`)

`inviteRepresentative(companyId, email, name)`:
1. Resolve company within the caller's tenant (RLS-scoped); 404 if not visible.
2. Call `RepresentativeInviter.invite(email, {tenantId, REPRESENTATIVE, companyId})`.
3. Persist `AppUser(id = externalUserId, tenantId, email, name, role = REPRESENTATIVE,
   status = INVITED)`.
4. Persist `RepresentativeLink(tenantId, userId = externalUserId, companyId)`. A second link for the
   same user → `ConflictException` (one-company-per-rep).
5. Write an `audit_entry` (actor, action `REPRESENTATIVE_INVITED`, entity, company).

`listRepresentatives(companyId)` → reps linked to that company (join `representative_link` →
`app_user`), tenant-scoped by RLS.

`AppUser` gains a `UserStatus.INVITED` value (ACTIVE | INACTIVE | INVITED).

### 3.4 Endpoints

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/api/v1/companies` | TENANT_ADMIN, EMPLOYEE | list companies (exists) |
| POST | `/api/v1/companies` | TENANT_ADMIN, EMPLOYEE | create company (exists) |
| GET | `/api/v1/companies/{id}` | TENANT_ADMIN, EMPLOYEE | company detail (exists) |
| PUT | `/api/v1/companies/{id}` | TENANT_ADMIN, EMPLOYEE | edit company (exists) |
| PATCH | `/api/v1/companies/{id}/status` | TENANT_ADMIN, EMPLOYEE | active/inactive (exists) |
| GET | `/api/v1/companies/{id}/treasury-accounts` | TENANT_ADMIN, EMPLOYEE | list (exists) |
| POST | `/api/v1/companies/{id}/treasury-accounts` | TENANT_ADMIN, EMPLOYEE | add (exists) |
| **GET** | **`/api/v1/companies/{id}/representatives`** | TENANT_ADMIN, EMPLOYEE | **list reps (new)** |
| **POST** | **`/api/v1/companies/{id}/representatives`** | TENANT_ADMIN, EMPLOYEE | **invite rep (new)** |

`RepresentativeResponse(id, email, name, status)`. Invite request `{ email, name }`.

### 3.5 Supabase access-token hook (delivered as documented SQL)

`infra/supabase/access_token_hook.sql` — the `custom_access_token_hook(event jsonb)` function that
copies `app_metadata.tenant_id`, `app_metadata.role`, `app_metadata.company_id` to top-level claims,
plus the `supabase_auth_admin` grants. README documents enabling it in the Supabase dashboard
(Authentication → Hooks). Not a Flyway migration (it requires dashboard wiring + auth-admin grants).

## 4. Frontend design

- **Companies list (`/companies`)** — existing table gains an **"Add company"** action opening a
  modal form (legal name, entity type, CUI, reg-no, address, locality, VAT status + period, tax
  regime, responsible employee). Rows link to `/companies/:id`. On success, invalidate the
  `companies` query.
- **Company detail (`/companies/:id`, new page + route)** — three sections:
  - **General info**: read view with inline edit (PUT) and an active/inactive toggle (PATCH).
  - **Treasury accounts**: list + add form (tax type, locality, IBAN, label).
  - **Representatives**: list (name, email, status pill) + "Invite representative" form (email,
    name) → POST; on success invalidate the reps query and show the new INVITED row.
- **API client**: typed functions for the company, treasury, and representative endpoints; TanStack
  Query hooks (`useCompanies`, `useCompany`, `useTreasuryAccounts`, `useRepresentatives`) and
  mutations. Reuse the existing `api<T>()` transport.
- **i18n**: RO/EN strings for the new labels.

## 5. Testing (TDD)

Backend (JUnit + Testcontainers/Postgres, run in CI; skip locally without Docker):
- Company create/list/get + treasury, all RLS-scoped to the tenant.
- `RepresentativeService.inviteRepresentative` with a **fake** `RepresentativeInviter`: asserts
  `app_user` (role REPRESENTATIVE, status INVITED) + `representative_link` created; second invite for
  the same user → `ConflictException` (one-company-per-rep).
- `SupabaseRepresentativeInviter` against `MockRestServiceServer`: asserts the `/auth/v1/invite` call
  and the `app_metadata` payload on the admin update.
- Cross-tenant isolation: tenant B cannot read tenant A's companies or representatives.

Frontend: `npm run lint` + `npm run build` (type-check) green.

## 6. Verification & constraints

- Docker is not installed locally, so backend integration tests run in CI (Docker present) or once
  `DB_URL` points at a Supabase Postgres. Frontend builds locally.
- Real Supabase invites are inactive until `SUPABASE_SERVICE_ROLE_KEY` is configured; until then the
  logging fallback drives the flow.

## 7. Build order

1. Backend model fixes: `AppUser` assigned id; `V2` representative_link PK; `UserStatus.INVITED`.
2. `RepresentativeInviter` port + logging fallback + Supabase adapter (+ config, `.env.example`).
3. `RepresentativeService` invite + list use-cases; controller endpoints; audit.
4. Backend tests (use-case, adapter, RLS, cross-tenant).
5. Frontend: API client + hooks; Add-company modal; Company detail page (3 sections).
6. `infra/supabase/access_token_hook.sql` + README note.
