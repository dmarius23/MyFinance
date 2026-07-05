# CLAUDE.md — MyFinance (repo-root, global brief)

Read this first every session. It is the **global** brief. **Layer-specific** rules live in
[`backend/CLAUDE.md`](backend/CLAUDE.md) and [`frontend/CLAUDE.md`](frontend/CLAUDE.md), which Claude Code
auto-loads when you work inside those folders — so backend work doesn't pull in frontend standards, and
vice versa. Keep this file lean and global; put layer detail in the nested files, not here.

## What we're building

A multi-tenant SaaS portal connecting **accounting firms** with their **client companies**. Client
representatives upload source documents; the system detects what's missing (reconciled against the bank
statement), auto-extracts amounts owed to the state from fiscal-declaration PDFs, generates monthly report
emails, and gives the firm a single monthly control tower over every client. See `README.md` for the
overview.

## Repository map

- **`backend/`** — Java 21 / Spring Boot 3 modular monolith + worker. **See [`backend/CLAUDE.md`](backend/CLAUDE.md)**
  for the backend stack, conventions, golden rules, and `backend/docs/` (design spec, clean-code guide,
  improvement plan, per-module design-history).
- **`frontend/`** — React 18 / Vite 5 / TypeScript PWA. **See [`frontend/CLAUDE.md`](frontend/CLAUDE.md)**
  for the frontend stack, conventions, and `frontend/docs/` (design spec, clean-code guide, improvement plan).
- **`docs/`** — repo-**global** source-of-truth (listed below).
- **`archive/`** — historical / pre-app artifacts (the original AirTable design). Not live; never required to build.
- **`supabase/`, `infra/`** — Supabase access-token hook + local RLS role bootstrap.

## Global golden rules (do not violate — apply in every layer)

1. **Multi-tenant isolation is the #1 invariant.** Never read or write across tenants. It is enforced in
   the database by PostgreSQL **row-level security** (backend detail in `backend/CLAUDE.md`); the browser
   is never the trust boundary (frontend detail in `frontend/CLAUDE.md`). Add a **cross-tenant isolation
   test for every new data-access path.**
2. **Representatives are scoped to their own company only**, enforced **server-side** — never trust the client.
3. **Extracted amounts are non-authoritative** until verified by reconciliation/confidence checks — never
   auto-send money figures that weren't validated.
4. **Secrets only via env/config**, never committed; **mask PII in logs**; payroll/PII is sensitive.

## Global source-of-truth docs (`docs/`)

- `docs/MyFinance-architecture-v1.md` — architecture, components, data model, ADRs (also `.docx`/`.html`).
- `docs/myfinance-accounting-portal-requirements-v1.0.html` — functional requirements (FR-001…), business
  rules, workflows, edge cases per module (also `.docx`).
- `docs/MyFinance-prototype.html` — clickable prototype: the visual + interaction reference for every screen.
- `docs/MyFinance-notes-enriched.md` — all decisions and rationale (incl. PoC extraction findings).
- `docs/MyFinance-architecture-review.md` — code-validated architecture walkthrough (a **dated snapshot**;
  §4 backend / §5–6 frontend/PWA / §7–11 cross-cutting).
- `docs/supabase-setup.md` — Supabase auth hook + JWT/RLS wiring; `docs/MyFinance-estimation-v1.xlsx` — effort.

## Product spine — module map & build order

The **MOD-01…15** breakdown is the spine; implement against it.

1. **Foundation:** scaffold, CI/CD, Supabase wiring, Flyway + RLS baseline, auth (MOD-01/02), company management (MOD-03).
2. **PoC core:** extraction (MOD-05) + intake & reconciliation (MOD-04) + state-payment email (MOD-07) — prove accuracy first.
3. **MVP:** reports (MOD-06), payroll (MOD-08), notifications (MOD-09), tasks (MOD-10), dashboards (MOD-11), audit/GDPR (MOD-12).
4. **MVP+:** chatbot + KB (MOD-13), PWA polish (MOD-14), i18n; cloud-folder ingestion (MOD-15).
5. **Harden:** end-to-end integration tests, security/RLS sweep, observability, deployment.

## Definition of done (every change)

Code + tests green; **RLS enforced and tested**; no PII in logs; acceptance criteria from the requirements
met. The layer-specific testing bars and conventions live in `backend/CLAUDE.md` and `frontend/CLAUDE.md`
and in the clean-code guide under each `*/docs/`.
