# CLAUDE.md — MyFinance build brief

This file orients Claude Code on every session. Read it first, then the architecture and requirements.

## What we're building

A multi-tenant SaaS portal connecting accounting firms with their client companies. See `README.md` for the overview and `MyFinance-architecture-v1.md` + the requirements doc for full detail. The module breakdown (MOD-01…14) is the spine — implement against it.

## Source-of-truth documents (read before coding)

All planning artifacts live in `docs/`.

- `docs/MyFinance-backend-design-v1.md` — **backend build spec**: project structure, data model (tables), module-by-module design, RLS/tenancy, extraction/reconciliation, jobs, build order. Implement the backend from this.
- `docs/MyFinance-frontend-design-v1.md` — **frontend/PWA build spec**: stack, routes/pages mapped to the prototype, components, auth, i18n, PWA (camera, web push). Implement the frontend from this.
- `docs/MyFinance-prototype.html` — clickable prototype: the visual + interaction reference for every screen.
- `docs/MyFinance-architecture-v1.md` — architecture, components, data model, ADRs.
- `docs/myfinance-accounting-portal-requirements-v1.0.html` — functional requirements (FR-001…), business rules, workflows, edge cases per module.
- `docs/MyFinance-notes-enriched.md` — all decisions and rationale (incl. PoC extraction findings).
- Sample documents for extraction fixtures are in `docs/` (Cerinte functionale + the AirTable screenshots); add the real BRD statement / BT Leasing invoice / Situație profit / D212 PDFs to `backend/src/test/resources/fixtures/` when building MOD-05.

## PoC findings (validated on real documents — see enriched notes)

Extraction is de-risked and almost entirely **non-AI**. Validated: BRD bank statement (text-PDF parse), BT Leasing invoice (text-PDF, IBAN+amount+period matching), Situație profit (table parse, self-cross-checks to totals), ANAF D212 (read embedded `d212.xml` named fields, self-validating → 10,520 RON). All declarations/statements are **readable PDFs** with **pluggable per-bank parsers and per-declaration XML mappers**. Reconciliation matching and transaction document-requirement classification are **deterministic, no LLM**; classification is adaptive (accountant overrides become per-company learned rules, two-way). **OCR (Claude vision) is used only for photographed receipts.** The only other AI surface is the rep chatbot.

## Golden rules (do not violate)

1. **Multi-tenant isolation is sacred.** Every table has `tenant_id`. Every query is tenant-scoped via PostgreSQL **row-level security**. Never write a query or endpoint that can read/write across tenants. Add a cross-tenant isolation test for every new data-access path.
2. **Representatives are scoped to their own company only.** Enforce server-side, never trust the client.
3. **Extracted amounts are non-authoritative.** OCR/vision output (receipts) and parsed values must pass reconciliation/confidence checks before they post or drive an email. Never auto-send money figures that weren't verified.
4. **No iText** (AGPL/commercial). Use Apache PDFBox + tabula-java; JAXB/Jackson-XML for e-Factura.
5. **Secrets only via config/env**, never committed. Sensitive PII + payroll = encrypted at rest; mask PII in logs.
6. **Backend is always-on** (Hetzner), not serverless — long-running workers must not be assumed ephemeral.

## Tech stack & conventions

- **Backend:** Java 21, Spring Boot 3 (Web, Security as resource server validating Supabase JWTs, Data JPA, Validation). Modular monolith — one package per module (`mod01_tenant`, `mod02_access`, …) with hexagonal layering: `domain` / `application` / `adapter` (persistence, web, external). External services (OCR, email, Gmail/Graph, LLM) sit behind ports.
- **Async work:** separate worker process, Redis (or Postgres pgmq) queue. Jobs idempotent (keyed by document/period/message id), exponential backoff + DLQ. Outbox pattern for email/notification dispatch.
- **DB:** Supabase Postgres. Migrations via Flyway. RLS policies are part of the schema, not an afterthought.
- **Frontend:** React + Vite SPA, TypeScript, PWA (camera capture, web push/VAPID). Bilingual (RO/EN) via i18n message catalogs. Talks to the API only.
- **Auth:** Supabase Auth (email+password, Google OAuth, TOTP MFA for staff). Backend validates JWTs; `tenant_id` + role as claims.
- **PDF parsing:** PDFBox (text + AcroForm/XFA), tabula-java (bank tables), JAXB (e-Factura). **Receipt OCR:** Claude vision via AWS Bedrock (EU); PaddleOCR fallback — behind one `ReceiptExtractor` port.
- **Email:** Amazon SES (EU), Thymeleaf templates, SPF/DKIM/DMARC. **Notifications:** Supabase Realtime (in-app) + Web Push (VAPID) + SES.

## Build order

1. **Foundation:** project scaffold, CI/CD, Supabase wiring, Flyway, RLS baseline, auth (MOD-01/02), company management (MOD-03).
2. **PoC core:** extraction engine (MOD-05) + intake & reconciliation (MOD-04) + state-payment email (MOD-07) on real sample documents — prove accuracy before expanding.
3. **MVP modules:** reports (MOD-06), payroll (MOD-08), notifications (MOD-09), tasks (MOD-10), dashboards (MOD-11), audit/GDPR (MOD-12).
4. **MVP+ :** chatbot + KB (MOD-13), PWA polish (MOD-14), i18n.
5. **Harden:** end-to-end integration tests, security/RLS test sweep, observability, deployment.

## Testing expectations

- Unit + integration per module; **contract** tests for the API; **cross-tenant isolation** tests are mandatory.
- For extraction/reconciliation, build a fixture set of real sample documents and assert extracted values + missing-doc detection.
- Zero secrets in code; run SAST/SCA/secret-scan in CI.

## Definition of done (per module)

Code + tests green, RLS enforced and tested, API documented (OpenAPI), no PII in logs, acceptance criteria from the requirements met, and the relevant user stories satisfied.
