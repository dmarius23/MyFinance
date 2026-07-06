# MyFinance — Architecture Review

**Reviewed: 5 July 2026** — point-in-time snapshot of the repository at commit `9ca791d` (branch `master`).

> ⚠️ **This is a snapshot in time and will go out of sync.** It describes the code as it stood on the
> date above. The codebase is under active development, so this review is perishable by design: as the
> currently-stubbed pieces get built (SES email, durable object storage, web push, the chatbot/KB,
> deployment IaC, job backoff/DLQ + the outbox relay) and as the database schema evolves past migration
> V28, statements here will drift from reality. Re-verify against the code before relying on any
> specific claim. The most perishable sections are §7 (Database schema), §10 (Docs vs. code), and §11
> (Risks / punch-list) — expect those to need refreshing first.

> Independent code-review / architecture walkthrough, written for someone getting up to speed on the
> codebase before contributing. **Every claim here was validated against the actual code**, not just
> the design docs — where the docs and the code disagree, this document follows the code and calls
> out the divergence. Read top-to-bottom, or jump to the [Database schema](#7-database-schema-the-backend-data-model)
> section if you just want the data model.
>
> Method: three parallel codebase-exploration passes (backend / frontend / DB-infra) plus firsthand
> reads of the security core, the representative-portal service, the cross-tenant isolation test, and
> the exact DDL of all 28 Flyway migrations.

---

## 1. TL;DR & maturity

**What it is:** a multi-tenant SaaS portal connecting **accounting firms** with their **client
companies**. Client representatives upload source documents (bank statements, invoices, receipts,
fiscal declarations, payroll); the system detects what's missing by reconciling against the bank
statement, auto-extracts amounts owed to the state from fiscal-declaration PDFs, generates monthly
report emails, and gives the firm a single monthly control tower over every client.

**Maturity:** the README calls this a "foundation scaffold." That undersells it substantially. This
is a **working MVP**:

| Metric | Count |
|---|---|
| Backend Java files | ~204 |
| Backend modules (business) | 14 (+ `common`) |
| Backend test files | 43 (incl. a real cross-tenant isolation test) |
| Frontend TS/TSX files | 72 |
| Flyway migrations | 28 |
| Live DB tables | 26 (25 tenant-scoped under RLS + 1 outbox) |

Most modules are functional end-to-end, not stubs. The gaps are at the edges (real email delivery,
durable file storage, deployment automation, a few TODO'd reliability features), **not** in the core
domain logic or the multi-tenancy model — which is the hard part, and it's done correctly.

**One-line verdict:** principled, security-conscious, genuinely built. Not AI slop. The main work
left is operational hardening, not architecture.

---

## 2. High-level architecture

The single most important structural fact: **there is exactly one backend API, and everything else
is a client of it.** The staff web app, the rep mobile PWA, the background worker, and the Google
Drive ingester all talk to the same Spring Boot service. **The browser never touches the database** —
it only holds a signed token and calls the API.

```
   ┌───────────────────┐     ┌───────────────────┐
   │  STAFF web app     │     │  REP mobile PWA    │   ← two React shells,
   │  (FirmLayout)      │     │  (RepHome)         │      ONE frontend codebase
   └─────────┬─────────┘     └─────────┬─────────┘
             │  HTTPS + "Authorization: Bearer <JWT>"
             │  (rep also sends  X-Company-Id: <id>)
             └───────────────┬───────────────┘
                             ▼
                   ┌────────────────────┐        ┌──────────────────┐
   login / token   │  Spring Boot API   │◀──JWKS─│  Supabase Auth    │
   ────────────────│  (stateless RS)    │ verify │  issues+signs JWT │
                   │                    │        │  (email, Google)  │
                   └───┬────────────┬───┘        └──────────────────┘
        connects as    │            │  enqueue jobs
     myfinance_app     ▼            ▼
    (RLS-subject) ┌─────────┐   ┌─────────┐   ┌────────────────────┐
                  │Postgres │   │  Redis  │──▶│  Worker (same jar,   │
                  │  (RLS)  │◀──────────────  │  web disabled)       │
                  └─────────┘                 └────────────────────┘
                       ▲  admin conn enumerates all tenants, then runs each per-tenant under RLS
                       └─── Google Drive ingester (scheduled)
```

| Layer | Technology | Notes |
|---|---|---|
| Backend | Java 21 / Spring Boot 3.5.9 | Modular monolith (hexagonal per module) + separate worker process |
| Frontend | React 18 / Vite 5 / TypeScript (strict) | SPA → installable PWA, served by nginx |
| Mobile | **PWA only — no native app** | Rep portal is mobile-first; camera via `<input capture>` |
| Database | Supabase Postgres (pgvector image) | Multi-tenancy via **PostgreSQL Row-Level Security** |
| Auth | Supabase Auth | Backend is a stateless JWT resource server; it never issues tokens |
| Queue | Redis | Job hand-off between API and worker |
| PDF | Apache PDFBox 3.0.3 | **No iText** (AGPL) — verified |
| Receipt OCR | Claude vision (direct Anthropic API) | Behind a `ReceiptExtractor` port; `Noop` fallback |
| Ingestion | Google Drive (service account) | Scheduled folder polling |

---

## 3. How it all connects (the wiring)

### 3.1 Identity flow (FE → BE → DB) — the spine everything hangs off

1. The **frontend** logs in via **Supabase Auth** (email/password or Google). Supabase returns a
   **JWT**. A Supabase *access-token hook* stamps three custom claims into that token: `tenant_id`,
   `role`, and (for representatives) `company_id`.
2. **Every API call** attaches that token as a `Bearer` header — done centrally in
   `frontend/src/lib/apiClient.ts`.
3. The **backend** validates the token's signature against Supabase's public keys (JWKS) — it never
   issues tokens itself (`common/config/SecurityConfig.java`). A filter
   (`common/security/TenantContextFilter.java`) copies the claims into a per-request `TenantContext`
   (a `ThreadLocal`), always cleared in a `finally` block.
4. When the request borrows a DB connection, `common/security/RlsDataSource.java` runs
   `set_config('app.tenant_id', …)` and `set_config('app.role', …)` on it. **PostgreSQL Row-Level
   Security** then silently filters every query to that tenant. The GUCs are reset when the
   connection returns to the pool, so identity never leaks across the pool.

The tenant boundary is therefore enforced at the **database**, derived from a **cryptographically
signed token** — not from anything the browser can tamper with.

### 3.2 A synchronous request — rep photographs a receipt on their phone

```
RepHome (PWA)                 API                              DB / storage
─────────────                 ───                              ────────────
tap camera  ── <input capture> opens native camera
pick photo  ── POST /portal/documents (multipart)
                + Bearer JWT + X-Company-Id
                     │
                     ├─ validate JWT → tenant_id, role=REPRESENTATIVE, userId
                     ├─ PortalService.companyId():
                     │     is X-Company-Id among this rep's representative_link rows? ─▶ DB (RLS)
                     │     no → 404 ; yes → use it
                     ├─ store bytes (LocalFsDocumentStorage) + INSERT document row  ─▶ DB (RLS)
                     ├─ notify the firm (INSERT notification row)
                     └─ 200 { docView } ───────────────────▶ React Query cache updates, UI shows it
```

Note the **two independent guards**: the app-level "is this company yours?" check *and* the
DB-level tenant RLS underneath it.

### 3.3 An asynchronous flow — heavy work off the request path

Uploads and extraction don't block the HTTP response. The pattern is enqueue-now, process-later:

```
API: document uploaded ── enqueue "EXTRACT_DOCUMENT" ─▶ Redis ─▶ Worker picks it up
                                                                  ├─ parse PDF (bank / invoice / tax)
                                                                  │   or Claude-vision the photo
                                                                  ├─ reconcile vs bank transactions
                                                                  └─ write results + a notification
Frontend finds out via:  React Query refetch (staff)  /  30-second polling (rep PWA)
```

The **worker is the same jar** as the API, launched with the web server disabled and the `worker`
profile on (`WorkerApplication.java`). Redis is the hand-off (`common/jobs/RedisJobQueue.java`).

### 3.4 Staff-web vs rep-mobile — same app, different façade

They are the **same SPA, same API, same auth**. The differences are deliberately small:

| | Staff web (`FirmLayout`) | Rep mobile (`RepHome`) |
|---|---|---|
| Backend surface | `/api/*` (all modules) | `/portal/*` (a narrow backend-for-frontend) |
| Scoping | tenant (all their companies) | tenant **+** one active company via `X-Company-Id` |
| Primary input | file pickers, tables | **camera capture**, upload-first UI |
| Live updates | React Query refetch | 30 s **polling** (no web push yet) |
| Shell | desktop "console" + sidebar | installable PWA, mobile chrome |

The rep portal is a **locked-down façade** over the same services — it can only ever reach the rep's
own company data, enforced server-side in `portal/application/PortalService.java`.

**The trust boundary in one sentence:** the browser is untrusted; it holds a signed token and calls
an API. All authority — who you are, which tenant, which company, what you may see — is re-derived on
the server from that token and enforced by Postgres RLS. Client-side role checks are UX only (the
code says so explicitly).

---

## 4. Backend

- **Shape:** modular monolith. One package per module under `ro.myfinance`, each with clean
  **hexagonal layering** — `domain` (entities, value objects, ports) / `application` (services,
  use-cases) / `adapter` (`web` controllers+DTOs, `persistence` repositories, `external` integrations).
  External services (OCR, email, cloud storage, Drive, user invites) sit behind **ports**, so a stub
  and a production adapter are interchangeable.
- **Two processes, one codebase:** `MyFinanceApplication` (web API) and `WorkerApplication` (jobs,
  web disabled). Redis-backed `JobQueue`.
- **Modules:** `tenant` (MOD-01), `access` (MOD-02 users/reps), `company` (MOD-03), `intake`
  (MOD-04 documents), `extraction` (MOD-05 parsing + reconciliation), `reports` (MOD-06),
  `taxpayments` (MOD-07), `payroll` (MOD-08), `notifications` (MOD-09), `tasks` (MOD-10),
  `dashboard` (MOD-11), `settings`, `ingestion` (Google Drive), `portal` (rep BFF), plus `common`
  (security, config, jobs, audit, web, pdf). Audit (MOD-12) lives in `common/audit`.

### 4.1 Extraction & reconciliation — the validated core (mostly deterministic, no LLM)

- **Bank statements** → pluggable per-format parsers selected by a `supports(text)` registry
  (`extraction/application/BankStatementParserRegistry.java`): **BRD**, **ING**, **MT940**,
  **CAMT.053** (ISO 20022 XML), plus a **generic running-balance** fallback.
- **Invoices** → `HeuristicInvoiceExtractor` (regex + PDFBox: supplier IBAN, total, date, client CIF,
  supplier name) enhanced by a dedicated **e-Factura** PDF parser (`EFacturaPdfParser`).
- **Tax declarations** → `AnafDeclarationExtractor` reads the **embedded XML** inside ANAF PDFs
  (D100 / D112 / D300), extracts per-obligation amounts, and **self-cross-checks** against the
  declared total; a mismatch flags the row for review.
- **Photographed receipts** → the **only AI surface**. `ReceiptExtractor` port with
  `AnthropicReceiptExtractor` (Claude vision, direct Anthropic Messages API) and a `NoopReceiptExtractor`
  fallback when no key is configured. The prompt returns structured JSON (issuer, CIFs, total,
  currency, date, receipt number, a confidence score, and whether the buyer CIF matches the company).
- **Reconciliation** (`extraction/application/ReconciliationService.java`, ~900 lines — the largest
  service): 3-tier matching — (1) IBAN + amount + date, (2) amount + supplier-name token, (3) unique
  exact amount — plus per-company **learned override rules** (`transaction_rule`), duplicate
  detection, and completeness/payment-status tracking. Split and partial payments are modeled via a
  per-match `allocated_amount`.

### 4.2 Ports — what's real vs stubbed

| Port | Real adapter | Stub/fallback | Status |
|---|---|---|---|
| Receipt OCR (`ReceiptExtractor`) | `AnthropicReceiptExtractor` (Claude vision) | `NoopReceiptExtractor` | ✅ real; Bedrock adapter (doc'd) not built |
| User invites (`UserInviter`) | `SupabaseUserInviter` (GoTrue admin API) | `LoggingUserInviter` | ✅ real |
| Cloud ingestion (`CloudFolderConnector`) | `GoogleDriveFolderConnector` (service account, JWT-bearer) | `FAKE` provider | ✅ real (read-only) |
| Email (`EmailSender`) | — | `LoggingEmailSender` | ⚠️ **stub only** — SES not wired, no AWS SDK in build |
| Document storage (`DocumentStorage`) | `LocalFsDocumentStorage` | — | ⚠️ **local filesystem only** — no S3/Supabase Storage |

---

## 5. Frontend

- **Stack:** React 18, Vite 5, TypeScript (strict), React Router 6, **TanStack React Query 5**
  (server state), Supabase JS (auth only), **react-i18next** (RO default, EN fallback),
  `vite-plugin-pwa`. Zustand is a dependency but currently **unused**.
- **Two shells:** `FirmLayout` — dark "console" for staff (sidebar + topbar + month stepper) across
  ~13 routes (Dashboard, Companies, CompanyDetail, Statements, TaxPayments, Payroll, Reports, Tasks,
  Notifications, Team, Settings, DataSources, + a `/admin/tenants` placeholder). `RepHome` — a
  separate mobile-first PWA for client reps.
- **This is a real working UI**, not placeholders — rich modals for reconciliation, file management,
  email preview, and charts (Recharts). The only real stubs are the `CompanyDetail` sub-cards and
  `/admin/tenants`.
- **API layer:** a thin `fetch` wrapper (`lib/apiClient.ts`) injects the Supabase Bearer token and an
  `X-Company-Id` header and parses RFC-7807 errors; ~15 typed API modules under `src/api/`.
- **Auth:** Supabase email/password + Google OAuth. Role guards (`auth/RequireRole.tsx`) are
  **client-side UX only — the server is authoritative** (stated in the code). TOTP MFA exists in the
  data model but has no enrollment UI.
- **State:** React Query for all server data (`staleTime` 30 s, no refetch-on-focus); "active
  company" (`lib/activeCompany.ts`, localStorage + `X-Company-Id`) and "active period/month"
  (`lib/period.tsx` context) are the two cross-cutting client states.

---

## 6. Mobile / PWA reality

Be precise here, because "mobile" is easy to over-read:

- **There is no native app.** No React Native / Flutter / Capacitor anywhere. "Mobile" = the
  installable **PWA** (`RepHome`), with a web manifest, a Workbox service worker (precaches the app
  shell; never caches `/api`), and home-screen icons.
- **Camera:** via `<input type="file" accept="image/*" capture="environment">` (the native picker) —
  *not* `getUserMedia`. This is the reliable cross-platform path.
- **Web Push (VAPID): not implemented.** The rep portal **polls every 30 seconds** instead.
- **No offline upload queue.** The app shell is cached, but a submit made while offline just fails
  (no background-sync/retry). React Query handles in-session retries only.

Both push and offline-sync are described in the design docs as intended; neither is built yet.

---

## 7. Database schema (the backend data model)

This is the part most worth internalizing to work on the backend. Postgres, schema owned by Flyway
(`backend/src/main/resources/db/migration/V1..V28`). Column types below are taken from the exact DDL.

### 7.1 The shape at a glance

```
                       ┌────────┐
                       │ tenant │  (top of every hierarchy; SUPER_ADMIN spans all)
                       └───┬────┘
        ┌──────────────────┼───────────────────────────┐
        ▼                  ▼                            ▼
   ┌──────────┐      ┌──────────┐               ┌──────────────────┐
   │ app_user │◀────▶│ company  │◀── children ──│ company_contact  │
   └────┬─────┘ rep_ └────┬─────┘               │ company_employee │
        │        link     │                      └──────────────────┘
        │  representative_link (user ↔ company, many-to-many)
        │                 │
        │                 ▼
        │        ┌──────────────────┐   the SHARED HUB: every uploaded file is a `document`
        │        │     document     │   (type = BANK_STATEMENT | INVOICE | RECEIPT | TAX_* |
        │        └───┬───┬───┬───┬──┘    TRIAL_BALANCE | PAYROLL | UNCLASSIFIED)
        │            │   │   │   │
        │   ┌────────┘   │   │   └──────────────┐
        ▼   ▼            ▼   ▼                  ▼
  bank_statement    invoice   tax_declaration   trial_balance
        │           (also holds photo receipts) │
        ▼               ▲                        ▼
  bank_transaction ──── transaction_invoice_match (M:N, with allocated_amount)
```

Everything else is per-tenant configuration (`general_settings`, `residence_treasury`,
`source_connection`) or append-only history (`*_email`, `document_reminder`, `notification`,
`audit_entry`, `import_file`).

### 7.2 Conventions used everywhere

- **PK:** `id uuid DEFAULT gen_random_uuid()` (except `general_settings`, keyed by `tenant_id`, and
  a couple of link tables).
- **Every tenant-scoped table** carries `tenant_id uuid NOT NULL REFERENCES tenant(id)`.
- **`period_month date`** is always the first of the month — the app is organized around monthly
  periods.
- **RLS** is enabled + `FORCE`d on every tenant table with a `tenant_isolation` policy (details in
  §8). Marked **RLS ✅** below.
- Money is `numeric(15,2)` (or `numeric(14,2)` for tax figures); rates are `numeric(5,2)`.

### 7.3 Tenant, access & company (MOD-01/02/03)

```
tenant                RLS ✅ (special policy: SUPER_ADMIN sees all)
  id uuid PK · name · cui · status(ACTIVE|SUSPENDED|ARCHIVED) · plan
  limits jsonb · branding jsonb · created_at

app_user              RLS ✅
  id uuid PK · tenant_id FK · email · name
  role(SUPER_ADMIN|TENANT_ADMIN|EMPLOYEE|REPRESENTATIVE) · status(ACTIVE|INACTIVE)
  mfa_enabled bool · phone(+V25) · last_login · created_at
  UNIQUE(tenant_id, email)
  ── NOTE: id is meant to equal the Supabase auth.users.id (see bootstrap SQL)

company               RLS ✅
  id uuid PK · tenant_id FK · legal_name · entity_type(SRL|PFA|SA|…) · cui
  reg_no · address · locality · vat_status · vat_period(MONTHLY|QUARTERLY)
  tax_regime(PROFIT|MICRO) · has_employees bool(+V12)
  responsible_user_id FK→app_user · status · created_at
  UNIQUE(tenant_id, cui)

company_contact       RLS ✅   id · tenant_id · company_id FK(CASCADE) · name · email · phone · role
company_employee      RLS ✅   id · tenant_id · company_id FK(CASCADE) · name · role
                                hired_on · terminated_on · status
                                ── lightweight HR registry; drives payroll relevance

representative_link   RLS ✅   ← evolved 3× (see §7.9)
  id uuid PK(+V26) · tenant_id FK · user_id FK→app_user(CASCADE) · company_id FK→company(CASCADE)
  created_at · UNIQUE(user_id, company_id)
  ── a rep ↔ companies join; a rep can serve MANY companies
```

### 7.4 Documents & extraction (MOD-04/05)

```
document              RLS ✅   ← the shared hub for every uploaded file
  id uuid PK · tenant_id FK · company_id FK · period_month
  type · source(REP|EMPLOYEE|INGEST|…) · status
  original_filename · content_type · size_bytes · storage_key · uploaded_by · uploaded_at

bank_statement        RLS ✅
  id uuid PK · tenant_id FK · document_id FK(UNIQUE, CASCADE) · company_id FK · period_month
  bank_code · account_iban · opening_balance · closing_balance
  status · cross_check_ok bool · txn_count · created_at

bank_transaction      RLS ✅
  id uuid PK · tenant_id FK · company_id FK · statement_id FK→bank_statement(CASCADE)
  txn_date · amount numeric(15,2) · direction(DEBIT|CREDIT) · partner_name · partner_iban
  description · ref · balance_after · account_iban(+V6)
  matched_document_id FK→document · requires_document bool · decision_source · category · override_reason

transaction_rule      RLS ✅   ← per-company LEARNED classification overrides
  id · tenant_id FK · company_id FK · match_iban · match_desc_norm
  requires_document bool · created_by · created_at
  UNIQUE(tenant_id, company_id, match_iban, match_desc_norm)

invoice               RLS ✅   ← also stores photographed RECEIPTS (V10)
  id uuid PK · tenant_id FK · document_id FK(UNIQUE, CASCADE) · company_id FK · period_month
  supplier_name · supplier_iban · total_amount · invoice_date · original_filename · status
  issuer_cif(+V10) · client_cif(+V10) · receipt_number(+V10)   -- receipt identifiers / dedup key
  wrong_party bool(+V11)   -- is this doc actually addressed to THIS company?

transaction_invoice_match   RLS ✅   ← M:N between payments and invoices
  id · tenant_id FK · transaction_id FK→bank_transaction(CASCADE) · invoice_id FK→invoice(CASCADE)
  source · allocated_amount numeric(15,2)(+V8) · created_by · created_at
  UNIQUE(transaction_id, invoice_id)
  ── allocated_amount enables partial payments / installments / split payments
```

### 7.5 Tax declarations & state payments (MOD-07)

```
tax_declaration       RLS ✅
  id · tenant_id FK · company_id · period_month · document_id
  type(D100|D112|D300) · cui · declared_total · computed_total · mismatch bool
  decl_period(+V17, the declaration's OWN period from its XML) · wrong_party bool(+V17)
  duplicate bool(+V18) · created_at
  UNIQUE(tenant_id, document_id)

tax_email             RLS ✅   ← append-only send history (one row per send)
  id · tenant_id FK · company_id · period_month · recipient · body · status(SENT|FAILED)
  error · declaration_ids (comma-separated ids covered) · sent_at · sent_by
```

### 7.6 Reports, payroll, reminders, notifications, tasks (MOD-06/08/09/10)

```
trial_balance         RLS ✅   ← computed monthly report snapshot (from uploaded trial balance)
  id · tenant_id FK · company_id · period_month · document_id · version
  balanced bool · report_json text · created_at · updated_at
  UNIQUE(tenant_id, company_id, period_month)

report_email          RLS ✅   append-only: id · tenant_id · company_id · period_month · recipient
                                · body · status · error · sent_at · sent_by
payroll_email         RLS ✅   append-only (payroll files reuse `document`, type=PAYROLL)
                                … · document_ids (comma-separated attached docs) · sent_at · sent_by
document_reminder     RLS ✅   append-only missing-doc reminder emails
                                … · recipient · body · status · error · sent_at · sent_by

notification          RLS ✅   ← in-app feed for firm staff (one row per recipient)
  id · tenant_id FK · recipient_user_id · type(DOCUMENT_UPLOADED|…) · title · body
  company_id · company_name · document_id · read_at · created_at

task                  RLS ✅   ← internal Kanban (TODO|IN_PROGRESS|DONE)
  id · tenant_id FK · title · details · assignee_id · company_id · due_date · status
  created_by · created_at · updated_at
```

### 7.7 Settings, treasury & ingestion

```
general_settings      RLS ✅   ← one row per tenant (PK = tenant_id)
  tenant_id PK/FK · vat_rate(21.00) · micro_rate(+V13, 3.00) · profit_rate(+V13, 16.00)
  sender_email(+V21) · updated_at

residence_treasury    RLS ✅   ← final form after 3 restructures (see §7.9)
  id · tenant_id FK · residence · iban_cam · iban_impozite · iban_cass · iban_cas · iban_tva
  UNIQUE(tenant_id, residence)
  ── one row per fiscal residence, one IBAN column per tax category

source_connection     RLS ⚠️ (USING only — see §8.4)   ← MOD-15 cloud-folder ingestion
  id · tenant_id FK · provider(GOOGLE_DRIVE|ONEDRIVE|FAKE) · display_name · root_folder_id
  forced_type · config text(+V28, was jsonb) · cursor · status · last_synced_at · last_result · created_at
  ── NO secrets stored here; the app auths with one service-account key from env

import_file           RLS ⚠️ (USING only)   ← per-file import ledger (idempotency + provenance)
  id · tenant_id FK · connection_id FK→source_connection(CASCADE) · source_ref (provider file id)
  source_etag · content_sha256 · filename · source_path · document_id FK→document
  status(IMPORTED|NEEDS_REVIEW|REJECTED|DUPLICATE) · detail · created_at
  UNIQUE(connection_id, source_ref)
```

### 7.8 Cross-cutting infrastructure

```
audit_entry           RLS ✅   append-only (tenant_id has NO FK — deliberate)
  id · tenant_id · actor_id · actor_role · action · entity · entity_id
  before jsonb · after jsonb · channel · at

outbox_message        RLS ❌   ← INTENTIONALLY not under RLS (cross-tenant relay infra)
  id · tenant_id (nullable, for routing) · aggregate_type · aggregate_id · type
  payload jsonb · status(PENDING|SENT|FAILED) · attempts · created_at · sent_at
  ── transactional outbox: written in the business txn, relayed by the worker.
     TODO in code: the relay should connect as a dedicated SYSTEM role; the relay loop
     itself is not yet wired (see risks).
```

### 7.9 Schema evolution worth knowing (why some migrations look odd)

- **`representative_link` flipped twice.** V1: composite PK `(user_id, company_id)` (many). V2:
  PK `user_id` — "a rep belongs to exactly one company." V26: reversed again to a surrogate `id` PK +
  `UNIQUE(user_id, company_id)` — reps can serve **many** companies. Current code is the many-company
  form; some comments still say "single company" (stale).
- **Treasury accounts were restructured three times.** `treasury_account` (per-company, V1) →
  dropped in V3 for `county_treasury_account` (per county+tax) → dropped in V14 for
  `residence_treasury_account` (residence + comma-list of tax types) → dropped in V15 for
  **`residence_treasury`** (one row per residence, a dedicated IBAN column per tax category). Only the
  last exists now. This is the kind of churn that tells you the fiscal-payment model was hard to pin
  down.
- **`invoice` doubles as the receipt table** (V10 added `issuer_cif` / `client_cif` /
  `receipt_number`; V11 added `wrong_party`). Photographed receipts are `invoice` rows.
- **`document` is the shared hub** — bank statements, invoices/receipts, tax declarations, trial
  balances, and payroll files all point back to a `document` row (or reuse it by `type`).
- **V9** is a one-time data cleanup (dedupe redundant bank statements); **V28** changed
  `source_connection.config` from `jsonb` to `text` (JPA binds the opaque JSON string as varchar,
  which Postgres won't implicitly cast to jsonb).

---

## 8. Multi-tenancy & security (the golden rule)

This is the strongest part of the codebase and it's implemented correctly **and tested**.

### 8.1 How isolation works end-to-end

- The app connects to Postgres as a **non-superuser, non-`BYPASSRLS` role** (`myfinance_app`). Flyway
  DDL and cross-tenant enumeration use a **separate admin pool** (`common/config/DataSourceConfig.java`).
- `RlsDataSource` sets `app.tenant_id` / `app.role` GUCs from the validated JWT on every borrowed
  connection and **resets them on close** (via a `Connection` proxy) so identity never leaks across
  the pool.
- **Fail-closed:** the policy expression is
  `tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid`. When unset, `nullif` returns
  `NULL`, every comparison is false, and the query returns **zero rows** — never fail-open.
- Every tenant table uses `ENABLE` **+ `FORCE`** ROW LEVEL SECURITY (so even the table owner is
  subject to it) with both `USING` and `WITH CHECK` (blocks cross-tenant reads *and* writes).
- The `tenant` table has a special policy: a `SUPER_ADMIN` sees all tenants; everyone else sees only
  their own row.

### 8.2 Representative scoping (golden rule #2)

`portal/application/PortalService.java` resolves the active company from the `X-Company-Id` header but
**validates it against the rep's actual `representative_link` rows**, rejects any company the rep
isn't linked to, fails closed if the user is `INACTIVE`, and re-checks ownership on download. So reps
get **two layers**: tenant RLS (DB) + company-membership check (app).

### 8.3 It's actually tested

`backend/src/test/java/ro/myfinance/CrossTenantIsolationTest.java` spins up a real Postgres via
Testcontainers, connects as `myfinance_app`, and asserts that tenant B can neither **read** nor
**`UPDATE`** tenant A's rows even with no `WHERE tenant_id` filter. This is the mandatory golden-rule
test, and it passes (skips gracefully when Docker is absent).

### 8.4 Hardening gaps (calibrated — none are currently exploitable)

- **Company-level rep scoping is app-layer only.** RLS enforces *tenant*, not *company*. A future
  rep-facing endpoint that forgets the `PortalService` company check would be caught by tenant RLS but
  *not* by company RLS. Not exploitable today (the client has no direct DB access; all current
  endpoints validate) — but a DB policy keyed on `representative_link` would be defense-in-depth.
- **`source_connection` / `import_file` declare only `USING`** (V27), unlike the other 23 tables which
  spell out `WITH CHECK` too. Postgres defaults `WITH CHECK` to the `USING` expression, so writes are
  *still* tenant-constrained — this is a cosmetic inconsistency, not a hole, but worth normalizing.
- **`outbox_message` relay** is designed to run under a dedicated SYSTEM role; that role and the relay
  loop aren't wired yet (TODO in the migration).
- **Payroll PII** is not field-encrypted; confirm PII log-masking before production.

---

## 9. Infrastructure, CI & secrets

- **Local dev:** `docker-compose.yml` brings up Postgres (`pgvector/pgvector:pg16`), Redis, backend,
  worker, and frontend (nginx). Backend runs Flyway on startup; the `local` profile seeds a demo
  tenant (`db/seed/V1000__dev_seed.sql`). The backend image bakes in Tesseract (local OCR fallback).
- **CI** (`.github/workflows/ci.yml`): backend `mvn verify` (with Testcontainers), frontend
  lint + build + typecheck, and a **gitleaks** secret scan. **No SAST/SCA** (no dependency-vuln or
  static-analysis step) yet — a gap vs the stated testing expectations.
- **Secrets:** clean. All externalized via env vars; `.env` is gitignored (`!.env.example` kept); no
  real keys committed — only local `postgres/postgres` and `myfinance_app/myfinance_app` dev defaults.
  The Supabase anon key in the SPA is public by design.
- **Supabase wiring:** a custom **access-token hook** (`supabase/access_token_hook.sql`) lifts
  `tenant_id` / `role` / `company_id` from `auth.users.raw_app_meta_data` into JWT claims — **it must
  be enabled in the Supabase dashboard** or every secured endpoint returns 401. First admin is
  bootstrapped manually (`supabase/bootstrap_first_admin.sql`): create the auth user, stamp the
  claims, insert a matching `app_user` row whose `id` equals the auth user id.
- **Deployment:** the docs describe Hetzner (backend+worker) + Vercel/Cloudflare (frontend) +
  Supabase Frankfurt, but **there is no infrastructure-as-code in the repo** (no Terraform/Ansible/k8s/
  Vercel config). Provisioning is currently manual/aspirational.

---

## 10. Docs vs. code — where the design docs oversell

| Documented | Reality in code |
|---|---|
| "AI chatbot + knowledge base" (MOD-13) | **Not built** — zero chatbot/embedding refs; `pgvector` is only the DB image |
| Amazon SES email delivery | **Logging stub only** (`LoggingEmailSender`); no AWS SDK in the build |
| Supabase/S3 document storage | **Local filesystem only** (`LocalFsDocumentStorage`) |
| Bedrock (EU) receipt OCR | Direct **Anthropic API** wired; Bedrock adapter not built |
| Jobs: idempotent, backoff, DLQ, outbox relay | Redis queue works; **backoff / DLQ / idempotency / relay loop are TODO** |
| TOTP MFA for staff | Schema flag only, no UI |
| Web Push notifications | **30-second polling** instead |
| Gmail/Graph email ingestion | Only **Google Drive** folder ingestion is built |
| Hetzner/Vercel deployment | **No deployment automation in the repo** |

---

## 11. Strengths & risks

### Strengths (genuinely good)
- Correct **and tested** multi-tenant isolation (RLS + fail-closed + cross-tenant test).
- Clean hexagonal modularity with swappable ports — stubs can become real adapters without touching
  business logic.
- Deterministic, auditable extraction (LLM only for photo receipts); a sophisticated reconciliation
  engine (3-tier + learned rules + allocations).
- Disciplined secret hygiene with secret-scanning in CI; sensible web/worker split.

### Risks / punch-list to production (roughly prioritized)
1. **Operational blockers:** email isn't actually sent (SES unwired); no durable object storage (local
   FS only); no deployment automation.
2. **Reliability:** job failures have no backoff/DLQ; the transactional outbox is written but not
   relayed — so "reliable email/notification dispatch" isn't reliable yet.
3. **Security hardening:** add SAST/SCA to CI; add a rep→company RLS policy (defense-in-depth);
   field-encrypt payroll PII; verify PII log masking.
4. **Frontend quality:** **no automated tests at all**; no error boundary; accessibility not addressed;
   notifications are polling, not push.
5. **Maintainability:** a few very large units (`ReconciliationService` ~900 lines, `RepHome` ~484
   lines) and stale post-refactor comments (e.g. `RlsConnectionProvider`, PortalService "single
   company").

---

*Prepared as a read-only architecture review on **5 July 2026** (commit `9ca791d`, branch `master`).
No application code was changed in producing this document — it reflects the repository as of that
date only, and will need refreshing as the implementation moves forward.*
