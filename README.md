# MyFinance — Accounting Portal

A multi-tenant SaaS portal connecting **accounting firms** with the **client companies** whose accounting they manage. Client representatives upload source documents; the system tells them in real time what's missing, auto-extracts the amounts owed to the state from fiscal declarations, generates and emails reports, and gives the firm a single monthly control tower across every client.

> Status: **Planning complete → ready to build (PoC).** Built primarily with Claude Code.

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

All planning artifacts are in `docs/`.

| Doc | File |
|---|---|
| Business requirements | `docs/myfinance-accounting-portal-requirements-v1.0.docx` / `.html` |
| Solution architecture | `docs/MyFinance-architecture-v1.md` / `.docx` / `.html` |
| Backend build spec | `docs/MyFinance-backend-design-v1.md` |
| Frontend / PWA build spec | `docs/MyFinance-frontend-design-v1.md` |
| Clickable prototype (UX reference) | `docs/MyFinance-prototype.html` |
| Enriched planning notes (+ PoC findings) | `docs/MyFinance-notes-enriched.md` |
| Cost estimation | `docs/MyFinance-estimation-v1.xlsx` |
| Build brief for Claude Code | `CLAUDE.md` (repo root) |
| Getting started in Claude Code | `GETTING-STARTED-CLAUDE-CODE.md` (repo root) |

## Extraction — validated on real documents (PoC)

Bank statement (BRD), invoice (BT Leasing), trial balance / profit situation, and ANAF declaration (D212) all parse from **readable PDFs / embedded XML** — deterministic, self-validating, **no AI**. OCR is needed only for photographed receipts. Reconciliation and the per-transaction "needs invoice?" decision are deterministic and adaptive (the accountant's overrides become learned rules). See the enriched notes for details.

## Phasing

- **PoC** — de-risk extraction + reconciliation on real sample documents.
- **MVP** — all modules (MOD-01…14), multi-tenant.
- **Future** — WhatsApp, e-Factura/ANAF SPV, in-app messaging, native app, DB-per-tenant tier.

## Getting started (high level)

1. Provision Supabase (EU) — DB, Auth, Storage; enable RLS.
2. Configure env: Supabase keys, SES, Bedrock (OCR/LLM), Gmail/Graph OAuth.
3. Run the Spring Boot backend + worker; run the React PWA against the API.
4. See `CLAUDE.md` for build order and conventions.

> Estimation note: development is performed by Claude Code; effort is tracked as human oversight/review hours. See the estimation workbook.
