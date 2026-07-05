# MyFinance — Accounting Portal

A multi-tenant SaaS portal connecting **accounting firms** with the **client companies** whose accounting they manage. Client representatives upload source documents; the system tells them in real time what's missing, auto-extracts the amounts owed to the state from fiscal declarations, generates and emails reports, and gives the firm a single monthly control tower across every client.

> Status: **Foundation scaffold in place** (monorepo: Spring Boot backend + React/Vite PWA, RLS-enforced multi-tenancy, MOD-01/02/03 stubs, Docker + CI). Built primarily with Claude Code.

## Why it exists

The firm's current tool lacks two things this product is built around:

1. **Client-driven document upload** with automatic detection of missing receipts/invoices (reconciled against the bank statement).
2. **Automatic extraction of amounts owed to the state** from fiscal-declaration PDFs (today typed by hand into a form).

It is multi-tenant from day one so it can be sold to multiple accounting firms.

## Key features (MVP)

- Tenant administration (provision accounting-firm tenants, plans, feature flags)
- Users, roles & access — Supabase Auth (email+password, Google OAuth, TOTP MFA)
- Client company management — tax profile, treasury accounts, lightweight HR registry
- Document intake & reconciliation — rep/employee upload + email-ingestion agent; missing-doc detection
- Document extraction — PDFBox/tabula for PDFs, Claude vision for photo receipts
- Financial statements & reports — trial balance → branded monthly PDF → email + sent-status
- Fiscal declarations & state payments — extract amounts → verify → templated payment email
- Payroll distribution, notifications (email + in-app), internal tasks, dashboards
- Audit, security & GDPR (retention-aware erasure), AI chatbot + knowledge base, PWA

## Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 21 / Spring Boot 3 (modular monolith + async workers) |
| Hosting | Hetzner (EU, always-on) — backend + worker |
| Data / Identity | Supabase (Postgres + RLS + pgvector, Auth, Storage) — Frankfurt |
| Frontend | React + Vite SPA → installable PWA (Vercel/Cloudflare) |
| Queue / cache | Redis (or Postgres pgmq) |
| PDF parsing | Apache PDFBox + tabula-java + JAXB (e-Factura XML) |
| Receipt OCR | Claude vision (AWS Bedrock, EU) · PaddleOCR fallback |
| Email | Amazon SES (EU) · Gmail/Graph (ingestion) |
| Notifications | Supabase Realtime · Web Push (VAPID) · SES |

Multi-tenancy: shared database, `tenant_id` on every row, enforced by PostgreSQL **row-level security**.

## Documentation

**Global** planning artifacts live in `docs/`. **Backend-** and **frontend-specific** build specs and
coding standards live in `backend/docs/` and `frontend/docs/`, alongside a layer `CLAUDE.md` that Claude
Code auto-loads only when working in that folder.

| Doc | File |
|---|---|
| Business requirements | `docs/myfinance-accounting-portal-requirements-v1.0.docx` / `.html` |
| Solution architecture | `docs/MyFinance-architecture-v1.md` / `.docx` / `.html` |
| Enriched planning notes (+ PoC findings) | `docs/MyFinance-notes-enriched.md` |
| Architecture review (code-validated, dated snapshot) | `docs/MyFinance-architecture-review.md` |
| Clickable prototype (UX reference) | `docs/MyFinance-prototype.html` |
| Cost estimation | `docs/MyFinance-estimation-v1.xlsx` |
| **Backend** — design spec · clean-code · improvement plan · design-history | `backend/docs/` (+ `backend/CLAUDE.md`) |
| **Frontend** — design spec · clean-code · improvement plan | `frontend/docs/` (+ `frontend/CLAUDE.md`) |
| Global build brief for Claude Code | `CLAUDE.md` (repo root) |
| Getting started in Claude Code | `GETTING-STARTED-CLAUDE-CODE.md` (repo root) |
| Historical / pre-app artifacts (original AirTable design) | `archive/` |

## Extraction — validated on real documents (PoC)

Bank statement (BRD), invoice (BT Leasing), trial balance / profit situation, and ANAF declaration (D212) all parse from **readable PDFs / embedded XML** — deterministic, self-validating, **no AI**. OCR is needed only for photographed receipts. Reconciliation and the per-transaction "needs invoice?" decision are deterministic and adaptive (the accountant's overrides become learned rules). See the enriched notes for details.

## Phasing

- **PoC** — de-risk extraction + reconciliation on real sample documents.
- **MVP** — all modules (MOD-01…14), multi-tenant.
- **Future** — WhatsApp, e-Factura/ANAF SPV, in-app messaging, native app, DB-per-tenant tier.

## Repository layout (monorepo)

```
MyFinance/
├─ CLAUDE.md       global build brief (auto-loaded every session)
├─ backend/        Java 21 / Spring Boot 3 — modular monolith + worker (Maven)
│  ├─ CLAUDE.md    backend layer brief (auto-loaded for backend work)
│  ├─ docs/        backend design spec · clean-code · improvement plan · design-history/
│  └─ src/main/java/ro/myfinance/
│     ├─ common/{config,security,web,jobs}   security, RLS, tenancy, error handling, queue
│     └─ tenant · access · company · intake · extraction · … · portal   (domain / application / adapter)
├─ frontend/       React 18 + Vite + TS — PWA (routing, auth guards, TanStack Query, i18n)
│  ├─ CLAUDE.md    frontend layer brief (auto-loaded for frontend work)
│  └─ docs/        frontend design spec · clean-code · improvement plan
├─ infra/db/init/  Postgres role bootstrap for local RLS
├─ docs/           GLOBAL planning artifacts (requirements, architecture, prototype, review, notes)
├─ archive/        historical / pre-app artifacts (original AirTable design)
├─ docker-compose.yml   db (pgvector) + redis + backend + worker + frontend
└─ .github/workflows/ci.yml   backend test · frontend build · secret scan
```

A single repo: backend and frontend share the OpenAPI contract by path, change atomically in one
PR, and build in one CI pipeline. See the chat history / commit for the rationale.

## Quickstart (local dev)

Prerequisites: **JDK 21** (the project targets 21; it compiles on a newer JDK), **Maven 3.9+**,
**Node 20+**, and **Docker** (for Postgres/Redis and the RLS test).

```bash
# 1. Backing services (Postgres w/ pgvector + Redis)
docker compose up -d db redis

# 2. Backend — runs Flyway (incl. RLS) + loads demo seed under the 'local' profile
cd backend && SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
#    → http://localhost:8080/actuator/health   ·   Swagger UI: /swagger-ui.html

# 3. Worker (separate process, no web server)
cd backend && mvn spring-boot:run -Dspring-boot.run.main-class=ro.myfinance.WorkerApplication

# 4. Frontend
cd frontend && npm install && npm run dev      # → http://localhost:5173

# Or the whole stack at once:
docker compose up --build
```

**Auth:** secured API endpoints return 401 until you point the backend at a Supabase project —
set `SUPABASE_JWKS_URI` (backend) and `VITE_SUPABASE_URL` / `VITE_SUPABASE_ANON_KEY` (frontend).
The tenant claims (`tenant_id`, `role`, `company_id`) come from a Supabase access-token hook.
Copy `.env.example` → `.env` to configure. **Never commit real secrets.**

**Representative invites** use the Supabase Auth admin API: set `SUPABASE_URL` + `SUPABASE_SERVICE_ROLE_KEY` (backend) and apply `infra/supabase/access_token_hook.sql` in your Supabase project, then enable it under Authentication → Hooks. Until configured, invites use a local logging fallback.

**Tests:** `cd backend && mvn test` — includes the mandatory cross-tenant RLS isolation test
(skipped automatically when Docker is unavailable). `cd frontend && npm run lint && npm run build`.

> Estimation note: development is performed by Claude Code; effort is tracked as human oversight/review hours. See the estimation workbook.
