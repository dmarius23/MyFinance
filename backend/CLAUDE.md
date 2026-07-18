# backend/CLAUDE.md — MyFinance backend

Loaded **in addition to** the root [`/CLAUDE.md`](../CLAUDE.md) (global rules) whenever you work under
`backend/`. This is the **backend layer**: stack, conventions, golden rules, and doc pointers. It does not
repeat the root — read that too.

## Source-of-truth docs (`backend/docs/`)

- [`MyFinance-backend-design-v1.md`](docs/MyFinance-backend-design-v1.md) — backend build spec: project
  structure, data model (tables), module-by-module design, RLS/tenancy, extraction/reconciliation, jobs,
  build order. Implement the backend from this.
- [`MyFinance-folder-ingestion-design-v1.md`](docs/MyFinance-folder-ingestion-design-v1.md) — MOD-15
  cloud-folder (Google Drive) ingestion design.
- [`MyFinance-tenant-storage-strategy-design-v1.md`](docs/MyFinance-tenant-storage-strategy-design-v1.md) —
  per-tenant document storage strategy (Supabase-only / Drive-mirror / Drive-primary).
- [`MyFinance-global-reference-settings-design-v1.md`](docs/MyFinance-global-reference-settings-design-v1.md) —
  **ready-to-implement brief**: move tax rates + treasury IBANs to global, super-admin-managed,
  effective-dated reference tables (tenants read-only); `sender_email` stays per-tenant.
- [`backend-clean-code-guidelines.md`](docs/backend-clean-code-guidelines.md) — **conventions to follow
  for all new or changed backend code.**
- [`backend-improvement-plan.md`](docs/backend-improvement-plan.md) — prioritized hardening/refactor
  roadmap (steps S1…S19); pick one at a time.
- [`design-history/`](docs/design-history/) — per-module implementation plans + design specs (build history).

## Stack & conventions

- **Java 21, Spring Boot 3** (Web, Security as a resource server validating Supabase JWTs, Data JPA,
  Validation).
- **Modular monolith** — one package per module under `ro.myfinance` (`tenant`, `access`, `company`,
  `intake`, `extraction`, `reports`, `taxpayments`, `payroll`, `notifications`, `tasks`, `dashboard`,
  `settings`, `ingestion`, `portal`) plus `common`. **Hexagonal layering:** `domain` / `application` /
  `adapter{web,persistence,external}`. External services (OCR, email, storage, Drive, invites) sit behind
  **ports** in `application`, implemented in `adapter/external`.
- **Two processes, one jar:** web API (`MyFinanceApplication`) + worker (`WorkerApplication`, web
  disabled). Redis-backed job queue.
- **DB:** Supabase Postgres; migrations via **Flyway**; RLS policies are part of the schema, not an
  afterthought. `open-in-view: false`.
- **PDF:** PDFBox (text + AcroForm/XFA) + tabula-java (bank tables); JAXB/Jackson-XML for e-Factura.
- **Receipt OCR:** Claude vision behind a `ReceiptExtractor` port (Noop fallback). **Email:** an
  `EmailSender` port (logging stub today; SES adapter is the production target).

## Backend golden rules (extend the global rules)

- **RLS is the tenant boundary.** Every tenant table has `tenant_id`, RLS `ENABLE` **+ `FORCE`**, and a
  fail-closed policy; the app connects as a non-`BYPASSRLS` role. A **cross-tenant isolation test is
  mandatory** for every new data-access path.
- **Extracted amounts are non-authoritative** until reconciliation/confidence checks pass — never
  auto-send unverified money figures.
- **No iText** (AGPL). PDFBox + tabula + JAXB only.
- **Backend is always-on** (Hetzner) — workers are long-running, not ephemeral; jobs are idempotent
  (keyed by document/period/message id) with backoff + DLQ; the outbox is written in the business
  transaction and relayed by the worker.

## PoC extraction findings (validated on real documents — mostly non-AI)

Extraction is de-risked and almost entirely **deterministic**: BRD bank statement (text-PDF parse), BT
Leasing invoice (text-PDF, IBAN+amount+period matching), Situație profit (table parse, self-cross-checks
to totals), ANAF D212 (read embedded `d212.xml` named fields, self-validating → 10,520 RON). Pluggable
per-bank parsers + per-declaration XML mappers. Reconciliation matching and document-requirement
classification are deterministic (no LLM); classification is adaptive (accountant overrides become
per-company learned rules). **OCR (Claude vision) is used only for photographed receipts.** Fixtures: add
the real BRD / BT Leasing / Situație profit / D212 PDFs to `backend/src/test/resources/fixtures/`; the
historical AirTable source screenshots are in `/archive/`.

## Testing & definition of done

Unit + **Testcontainers** integration per module; **contract** tests for the API; **cross-tenant isolation
test mandatory**; extraction/reconciliation fixture assertions (extracted values + missing-doc detection).
DoD: tests green, RLS enforced and tested, OpenAPI current, no PII in logs, SAST/SCA/secret-scan in CI.
