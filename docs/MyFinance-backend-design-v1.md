# MyFinance — Backend Technical Design (v1)

Companion to `MyFinance-architecture-v1.md` and the requirements doc. This is the build spec Claude Code implements against for the **backend**. Frontend/PWA is in `MyFinance-frontend-design-v1.md`.

## 1. Stack & ground rules

- **Java 21, Spring Boot 3** — Web, Security (resource server validating Supabase JWTs), Data JPA, Validation, Scheduling.
- **Modular monolith** + a **separate worker process** (same codebase, different entrypoint) for async jobs.
- **Supabase** (EU/Frankfurt): PostgreSQL (+ Row-Level Security, pgvector), Auth, Storage.
- **Redis** (or Postgres `pgmq`) for the job queue + cache.
- **Flyway** migrations; RLS policies are part of the schema.
- **Hexagonal layering** per module: `domain` (entities, value objects, ports) / `application` (services, use-cases) / `adapter` (web, persistence, external).
- Golden rules (see CLAUDE.md): tenant isolation via RLS on every table; representatives scoped to their own company; extracted/AI amounts non-authoritative until verified; no iText; secrets via env; backend always-on.

## 2. Project structure

```
backend/
 ├─ pom.xml
 ├─ src/main/java/ro/myfinance/
 │   ├─ MyFinanceApplication.java        # web entrypoint
 │   ├─ WorkerApplication.java           # worker entrypoint (queue consumers)
 │   ├─ common/                          # security, tenancy context, error handling, config
 │   │   ├─ security/  (JwtAuthFilter, TenantContext, RlsConnectionInitializer)
 │   │   ├─ web/       (ApiExceptionHandler, ApiResponse)
 │   │   └─ jobs/      (Job, JobQueue port, Outbox)
 │   ├─ mod01_tenant/   {domain, application, adapter}
 │   ├─ mod02_access/
 │   ├─ mod03_company/
 │   ├─ mod04_intake/        # upload, reconciliation, transaction classification
 │   ├─ mod05_extraction/    # parsers behind ports (bank, invoice, declaration, balance, receipt-OCR)
 │   ├─ mod06_reports/
 │   ├─ mod07_statepay/
 │   ├─ mod08_payroll/
 │   ├─ mod09_notifications/
 │   ├─ mod10_tasks/
 │   ├─ mod11_dashboard/
 │   ├─ mod12_audit/
 │   └─ mod13_chatbot/
 └─ src/main/resources/db/migration/      # Flyway V1__..., includes RLS policies
```

## 3. Multi-tenancy & security (cross-cutting)

- Every tenant-scoped table has `tenant_id uuid not null`. **RLS policy** `USING (tenant_id = current_setting('app.tenant_id')::uuid)` on all of them.
- `JwtAuthFilter` validates the Supabase JWT, extracts `tenant_id`, `role`, `user_id`, and (for reps) `company_id`; stores them in a request-scoped `TenantContext`.
- A `RlsConnectionInitializer` runs `SET app.tenant_id = ?` (and `app.role`) at the start of each DB transaction so RLS is enforced even if application code forgets a filter.
- Roles: `SUPER_ADMIN` (cross-tenant, no tenant-data impersonation in MVP), `TENANT_ADMIN`, `EMPLOYEE`, `REPRESENTATIVE`.
- Representative requests are additionally constrained to `company_id`.
- **Mandatory test per data-access path:** a cross-tenant isolation test (tenant A cannot read/write tenant B).

## 4. Data model (core tables)

Keyed by `tenant_id`. Only the load-bearing columns are listed.

- **tenant**(id, name, cui, status, plan, limits jsonb, branding jsonb, created_at)
- **app_user**(id, tenant_id, email, name, role, status, mfa_enabled, last_login)
- **company**(id, tenant_id, legal_name, entity_type, cui, reg_no, address, locality, vat_status, vat_period, tax_regime, responsible_user_id, status)
- **company_contact**(id, company_id, name, email, phone, role)
- **company_employee**(id, company_id, name, role, hired_on, terminated_on, status)   # lightweight HR registry
- **treasury_account**(id, company_id, tax_type, locality, iban, label)
- **representative_link**(user_id, company_id)   # one rep → one company (MVP)
- **document**(id, tenant_id, company_id, period_month, type[BANK_STATEMENT|INVOICE|RECEIPT|TRIAL_BALANCE|DECLARATION|PAYROLL], source[REP|EMPLOYEE|EMAIL_AGENT], storage_key, status, uploaded_by, uploaded_at)
- **bank_statement**(id, document_id, company_id, period_month, bank_code, opening_balance, closing_balance)
- **bank_transaction**(id, tenant_id, company_id, statement_id, txn_date, amount, direction[DEBIT|CREDIT], partner_name, partner_iban, description, ref, balance_after, matched_document_id nullable, requires_document bool, decision_source[SYSTEM_RULE|LEARNED_RULE|ACCOUNTANT_SET], category, override_reason)
- **transaction_rule**(id, tenant_id, company_id, match_iban, match_desc_norm, requires_document, created_by, created_at)   # learned overrides
- **extraction_job**(id, tenant_id, document_id, status[PENDING|EXTRACTED|NEEDS_REVIEW|COMMITTED|FAILED], extractor, confidence, payload jsonb, cross_check_ok)
- **trial_balance**(id, company_id, period_month, document_id, version, lines jsonb, totals jsonb)
- **financial_report**(id, company_id, period_month, pdf_key, status[GENERATED|SENT|RESENT], generated_at)
- **fiscal_declaration**(id, company_id, period_month, decl_type[D212|D300|D301|D112|...], document_id, parsed jsonb, cross_check_ok)
- **tax_line**(id, declaration_id, tax_type, amount, treasury_account_id nullable)
- **monthly_tax_summary**(id, company_id, period_month, amounts jsonb, deadline, verification_status[NEVERIFICAT|VERIFICAT], email_status[NETRIMIS|TRIMIS], verified_by)
- **payroll_document**(id, company_id, period_month, document_id, employees_count, status, email_status)
- **document_request**(id, company_id, period_month, items jsonb, status[OPEN|FULFILLED], created_by)
- **notification**(id, tenant_id, company_id nullable, channel[EMAIL|IN_APP], rule_code nullable, recipient, status[QUEUED|SENT|DELIVERED|FAILED], payload jsonb, created_at)
- **email_log**(id, tenant_id, company_id, kind[REPORT|TAXPAY|PAYROLL|REMINDER|DOCREQUEST], subject, to_addr, period_month, status, sent_at)
- **notification_rule**(id, tenant_id, code, schedule, audience, channels, enabled bool)
- **task**(id, tenant_id, title, details, assignee_id, company_id nullable, due_date, status[TODO|IN_PROGRESS|DONE], created_at)
- **kb_article**(id, tenant_id, title, body, embedding vector, published bool)
- **audit_entry**(id, tenant_id, actor_id, actor_role, action, entity, entity_id, before jsonb, after jsonb, channel, at)   # append-only

`period_month` is a `date` normalized to the first of the month.

## 5. Module specs

### MOD-01 Tenant Administration (SUPER_ADMIN)
Endpoints: `POST/GET/PATCH /api/v1/admin/tenants`, suspend/reactivate/archive, set plan/limits/flags, invite initial tenant admin. Hard plan-limit enforcement. Suspending pauses scheduled jobs + email agent.

### MOD-02 Users, Accounts & Access
Auth via Supabase (email+password, Google OAuth, TOTP MFA for staff). Backend validates JWT only. Endpoints: invite user, list/deactivate users, assign role, link representative→company. Last tenant-admin cannot be deactivated. Counts enforce plan limits.

### MOD-03 Client Company Management
CRUD company + active/inactive; tax profile; treasury accounts per (tax_type, locality); contacts; HR registry. Deadlines: auto Romanian defaults (25th, VAT cadence) with per company/month override. CUI unique per tenant. Block state-pay email if a payable tax has no treasury account.

### MOD-04 Document Intake & Reconciliation  *(core differentiator)*
- Upload (rep/employee) → store to Supabase Storage → create `document` + enqueue `extract-document`.
- Email-ingestion agent (Gmail/Graph OAuth) polls, imports attachments, classifies, maps to company or **triage queue**.
- **Reconciliation**: match `bank_transaction` ↔ invoice/receipt via deterministic score on **(partner IBAN + amount + period)** with FX tolerance → exact = auto-match, near = suggest/confirm, none = unmatched.
- **Transaction document-requirement** (NO LLM): base rule engine (direction; Treasury IBAN; own-account transfer; salary/leasing/fee keywords; else supplier→invoice) → **learned rules** (`transaction_rule` keyed on partner IBAN + normalized description, created from accountant decisions) → **accountant override** always wins, audited, creates/updates a learned rule. Two-way: accountant can force "needs doc" or "no doc" on any transaction.
- Period status (Not started/Partial/Complete) = bank statement present AND every `requires_document && unmatched` resolved. Only those drive the missing-docs list + reminders.

### MOD-05 Document Extraction Engine  *(validated on real samples — see architecture §PDF parsing)*
One port per document type, deterministic where possible:
- `BankStatementParser` — readable-PDF text (PDFBox + tabula); **per-bank implementations** (BRD validated; BT/BCR/ING pluggable). Output: normalized transactions incl. partner IBAN + balance.
- `InvoiceParser` — text PDF / e-Factura XML (JAXB). Output: supplier, supplier IBAN, number, date, due date, total, VAT.
- `DeclarationExtractor` — read **embedded declaration XML** (e.g. `d212.xml`) via **per-type XML mapper** (D212/D300/D301/D112…). Output: amount per tax + period + deadline. **Cross-check** component taxes vs stated total.
- `TrialBalanceParser` — readable-PDF table; **cross-check** parsed lines vs stated totals (class 6/7 sums).
- `ReceiptExtractor` — **OCR only here**: Claude vision via AWS Bedrock (EU) default; PaddleOCR self-hosted fallback; per-field confidence.
Below-threshold OCR or failed cross-check → `NEEDS_REVIEW`. Every extraction audited (source → values → reviewer).

### MOD-06 Financial Statements & Reports
Upload trial balance → extract + cross-check → generate **branded monthly PDF** (formatted repackage, fixed template) → view/download → email to rep (SES, Thymeleaf) → `email_status`. Re-upload after send regenerates + flags. Rep sees own reports read-only.

### MOD-07 Fiscal Declarations & State Payments
Upload declaration PDFs (separate per tax) → extract (MOD-05) → aggregate `monthly_tax_summary` → **mandatory Verificat gate** by responsible person → compose templated email auto-filling amounts + deadline + treasury account per tax → send (SES) → `email_status`. Missing treasury account blocks send. Manual amount entry fallback.

### MOD-08 Payroll
Manual upload of payroll files per company/month → view/download → email to rep → status. Salary data = strongest access controls + field-level encryption + audit. Payslips to representative only (MVP).

### MOD-09 Notifications Engine
Channels **email + in-app** (WhatsApp Future, flag). Manual missing-doc reminder pre-filled from MOD-04. Preset `notification_rule`s, each enable/disable by admin (auto-send toggle). Document requests. Delivery tracking (`notification`, `email_log`). **Outbox pattern** for reliable dispatch; jobs idempotent.

### MOD-10 Internal Tasks
Employee-only, tenant-scoped. CRUD; statuses TODO/IN_PROGRESS/DONE; optional company link; overdue highlight + in-app nudge.

### MOD-11 Dashboards
Read-model/aggregates: per-company monthly row (docs/report/tax/payroll + open requests + overdue + last activity), filterable by month/status/responsible. **Section summary tiles** for the selected month — one per section (docs/tax/payroll/reports) with counts of companies fully-done / partial / not-started — plus a **new-companies tile** (onboarded this month: in-setup / finished), derived from each company's onboarding/created date. Tenant overview counts. Rep landing read-model. Suggested endpoint `GET /api/v1/dashboard?month=YYYY-MM` returns tiles + rows in one payload.

### MOD-12 Audit, Security & GDPR
Append-only `audit_entry` for all user + automated actions. Retention-aware deletion: anonymize non-required PII immediately, retain mandated accounting records for the statutory window, purge after. Encryption at rest (sensitive PII + payroll), TLS in transit, PII masked in logs.

### MOD-13 AI Chatbot + Knowledge Base
Rep-facing, retrieval-grounded over **own company data + admin-managed KB** (pgvector). Read-only, disclaimers, escalate on low confidence, audited, feature-flag gated. (Only AI surface besides receipt OCR.)

## 6. Async jobs (worker)
Queue jobs: `extract-document`, `reconcile-period`, `classify-transactions`, `generate-report`, `send-email`, `run-notification-rule`, `ingest-mailbox`. **Idempotent** (keyed by document/period/message id), exponential backoff + DLQ. Outbox for email/notification so a crash never double-sends.

## 7. API conventions
REST `/api/v1`, JSON, OpenAPI as contract source with a breaking-change CI gate. Errors via `ApiExceptionHandler` (RFC-7807-ish). Pagination on list endpoints. All money as integer minor units or `BigDecimal` (never float).

## 8. Testing & DoD
Unit + integration per module; **contract** tests; **mandatory cross-tenant isolation tests**; extraction/reconciliation fixture suite built from the real sample docs (BRD statement, BT Leasing invoice, Situație profit, D212) asserting extracted values, cross-checks, and missing-doc detection. SAST/SCA/secret-scan in CI. Per-module DoD: code+tests green, RLS enforced+tested, OpenAPI documented, no PII in logs, acceptance criteria met.

## 9. Build order
1. Foundation: scaffold, CI/CD, Supabase wiring, Flyway + RLS baseline, JWT/tenancy, MOD-01/02, MOD-03.
2. PoC core: MOD-05 (parsers: bank/invoice/declaration/balance + receipt OCR) + MOD-04 (reconciliation + classification) + MOD-07 email — assert against the real sample fixtures.
3. MVP: MOD-06, 08, 09, 10, 11, 12.
4. MVP+: MOD-13, i18n.
5. Harden: e2e, security/RLS sweep, observability, deploy (Hetzner + Supabase).
