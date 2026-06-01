# Project Notes — MyFinance Accounting Portal
# Version: enriched after brainstorming
# Date: 31 May 2026
# Language of deliverables: English | App UI: Romanian + English (bilingual)

## 1. Original Notes (summary of "Cerinte functionale.docx")

A portal for management and communication between an accounting firm and the legal entities
(client companies) whose accounting it handles. Three user types: (1) accounting-firm employees,
(2) client-company representatives, (3) portal administrator. Each client company has one or more
accounts to access the portal and upload documents.

Core capabilities described:
- Client reps upload bank statements, receipts (chitanțe), paid invoices (facturi) per month/quarter
  (PDF or photos). System identifies amounts and supplier/debtor data, compares against the bank
  statement, and shows in real time which documents are missing.
- Employee modules: company management (list/add, active/inactive); financial statements/reports by
  calendar month (upload trial balance → generate reports → view/download PDF → email to rep, with
  sent-status tracking); fiscal declarations (upload smart PDFs → extract amounts owed to the state →
  compose templated email with amounts + deadline + treasury accounts → send + status); payroll
  (state de plată) made visible for emailing to reps; document-completeness tracking + notifications
  (email/WhatsApp/in-app, manual + preset automatic); employee dashboard of all firms and status;
  internal tasks (simple).
- "Alte idei": payslip/tax-doc distribution from portal; email-ingestion agent; mobile app/PWA;
  in-app messaging; AI chatbot + knowledge base.
- Non-functional: audit of all actions (incl. automated); encryption of sensitive data; HTTPS/TLS;
  GDPR deletion on request; performance/concurrency to be quantified.
- Current situation: firm uses an existing app covering much of this. Missing: client document upload
  + missing-doc notification, and automatic determination of amounts owed to the state (today typed
  into a form — see AirTable-IInserareSumeCareTrebuiePlatiteCatreStat.png).

Supporting screenshots (Airtable): Impozite (per-company monthly taxes with Trimis/Netrimis +
Verificat/Neverificat statuses), Situații financiare (monthly, email sent status), Task-uri
(status, responsible person, create date, deadline).

## 2. Module Breakdown (locked)

- MOD-01 Tenant Administration — super-admin provisions accounting-firm tenants (MVP)
- MOD-02 Users, Accounts & Access — roles, auth, scoping (MVP)
- MOD-03 Client Company Management — companies, contacts, HR registry, tax profile, treasury accounts (MVP)
- MOD-04 Document Intake & Reconciliation — multi-channel upload + missing-doc detection (MVP)
- MOD-05 Document Extraction Engine — shared extraction/OCR/structured parsing (MVP)
- MOD-06 Financial Statements & Reports — trial balance → branded monthly PDF + email (MVP)
- MOD-07 Fiscal Declarations & State Payments — declaration extraction → templated payment email (MVP)
- MOD-08 Payroll (State de plată) — manual upload, view, email to rep (MVP)
- MOD-09 Notifications Engine — email + in-app (WhatsApp Future) (MVP)
- MOD-10 Internal Tasks — simple board, employee-only (MVP)
- MOD-11 Dashboards — employee control tower + rep landing page (MVP)
- MOD-12 Audit, Security & GDPR — audit log, encryption, retention-aware deletion (MVP)
- MOD-13 AI Chatbot + Knowledge Base — rep-facing, grounded Q&A (MVP, explore)
- MOD-14 Mobile / PWA — responsive web + installable PWA w/ camera + web push (MVP, explore)
- Future: WhatsApp channel; e-Factura/ANAF SPV; in-app messaging; native mobile app; per-employee
  payslip distribution; payroll sync/integration; template customization; per-employee access
  restriction; automated payslip/tax-doc distribution.

## 3. Key Decisions Log

Multi-tenancy: shared DB + tenant_id + Postgres row-level security + field-level encryption for
sensitive PII. DB-per-tenant reserved as a future premium tier. Billing: provisioning only in MVP.

MOD-01: no super-admin impersonation in MVP; HARD plan limits.
MOD-02: roles = Tenant Admin, Employee, Company Representative (+ platform super-admin). Auth =
email+password + Google OAuth (OIDC). MFA (TOTP) required for employees/admins; reps optional.
All employees see all companies in MVP, but data model carries a responsible-person link to enable
future per-employee restriction. One representative → one company. Invite-only reps.
MOD-03: lightweight client-employee/HR registry (name, role, hire/termination, status). Tax profile
per company drives applicable obligations. Treasury accounts stored per company keyed by tax type +
locality. Deadlines: auto Romanian defaults with manual override. CUI unique per tenant.
MOD-04: intake channels = rep upload, employee upload, email-ingestion agent (Gmail/M365 via OAuth).
Reconciliation: auto-match high-confidence bank↔doc pairs, queue exceptions for confirmation.
Cash receipts not on the bank statement supported. Per-company-per-period status (Not started /
Partial / Complete). Unmappable email imports → triage queue.
MOD-04 transaction document-requirement classification — ADAPTIVE RULES, NO LLM (validated on a real
BRD statement, Mar 2026). Each parsed bank transaction gets a needs-document decision with provenance:
  (1) SYSTEM_RULE — deterministic base rules from structured signals: incoming/credit = income (no doc);
      Treasury IBAN (…TREZ…)/CAM/contribuții/impozit/TVA = tax covered by declaration (no doc); transfer
      between the company's own IBANs = no doc; description "salariu" = covered by payroll (no doc);
      "leasing"/"rată" = needs leasing invoice; "comision"/"taxă" = bank fee; otherwise supplier debit =
      invoice/receipt required.
  (2) LEARNED_RULE — the rules are ADAPTIVE: when the accountant marks a transaction's requirement, the
      system stores a per-company learned rule keyed on (partner IBAN, normalized description). Matching
      transactions in later periods are auto-classified the same way, so the firm is never asked twice.
  (3) ACCOUNTANT_SET — manual decision; ALWAYS wins, is audited, and creates/updates the learned rule.
Precedence: accountant > learned > base. No LLM, fully deterministic and explainable. Only "needs
document AND unmatched" transactions drive the missing-docs list and client reminder emails. Money
figures never auto-drive an email without the accountant gate. Bank parsing stays deterministic
per-bank (BRD parser validated with pdfplumber on a text PDF; BT/BCR/ING/etc. behind one
BankStatementParser port). A future LLM classifier for the ambiguous long tail remains an option but is
explicitly out of scope.
MOD-05 extraction — VALIDATED ON REAL SAMPLES (all readable PDFs; OCR only for photo receipts):
  • Bank statement (BRD, Mar 2026): readable-PDF text parse via pdfplumber → all 6 transactions with date,
    amount, debit/credit, partner, partner IBAN, balance. Per-bank parser behind one BankStatementParser
    port (BRD done; BT/BCR/ING/etc. pluggable). Bank layouts vary but are all readable PDFs.
  • Invoice (BT Leasing): readable-PDF parse → supplier, supplier IBAN, invoice no., date, due date, 21%
    VAT, total 3,485.18. Matching to bank txn = deterministic score on (IBAN + amount + period); correctly
    REJECTED the April invoice against the March statement payment.
  • Trial balance / Situație profit (Innovatecode): readable-PDF table parse → account code+name+amount;
    self-cross-checks (class 6 = 7,802.02 cheltuieli, class 7 = 9,653.78 venituri, profit 1,851.76) match
    the document's stated totals to the cent → used as a validation gate before report generation.
  • ANAF declaration (D212): carries an EMBEDDED d212.xml; amounts read from named XML fields
    (impozit_venit_plus=800, cass_plus=9,720, dif_de_plata=10,520) and cross-checked (800+9,720=10,520).
    BEST-CASE path — official structured data, exact, self-validating. Multiple declaration types
    (D212/D300/D301/D112/...) via per-type XML mappers behind one extractor port.
Reconciliation matching and needs-document classification are BOTH deterministic — NO LLM. OCR (Claude
vision) is confined to photographed receipts only. Extraction is the project's biggest risk and it is
now largely retired on real documents.
MOD-05 (original note): bank statements TEXT PDFs; invoices structured PDF/XML; receipts photos (OCR);
ANAF declarations structural (embedded XML) with manual fallback. Per-field confidence;
below-threshold → human review. e-Factura/ANAF SPV architected-for-later, not MVP.
MOD-06: produce a formatted, branded repackage of the trial balance as the monthly PDF (no heavy
accounting computation). Fixed templates in MVP (customization Future). Rep sees own reports read-only.
Per-company-per-month email sent status.
MOD-07: separate declaration PDFs per tax, aggregated into a monthly tax summary. Mandatory
"Verificat" gate before sending. Email auto-fills amounts + deadline + treasury account per tax.
Missing treasury account blocks send. Manual amount entry as fallback. Sent-status tracked.
MOD-08: manual upload of payroll files; payslips emailed to representative only in MVP. Salary data
treated as most sensitive (encryption + strict access + audit).
MOD-09: email + in-app in MVP; WhatsApp Future (behind tenant feature flag). Preset notification
rules, each toggleable on/off by admin (auto-send can be disabled). Manual missing-doc reminders
pre-filled from MOD-04. Document-request feature. Delivery tracking.
MOD-10: three statuses (To do / In progress / Done). Optional link to a client company. Employee-only.
MOD-11: employee dashboard per-company monthly row = docs / report / tax / payroll + open document
requests + overdue flag + last contact/activity. Reps get a simple landing page.
MOD-12: immutable audit log incl. automated actions. Retention-aware deletion: anonymize non-required
PII immediately, retain legally-required accounting records for the retention window, full purge after.
TLS in transit, sensitive PII encrypted at rest.
MOD-13: chatbot for representatives; broader Q&A over their own account data + general fiscal Q&A from
an admin-managed knowledge base. Retrieval-grounded, read-only, disclaimers, escalate-to-human on low
confidence, audited, feature-flag gated. FLAGGED for a grounding/guardrails spike (highest AI risk).
MOD-14: responsive web + installable PWA (camera capture for receipts, web push). Native app Future.

## 4. Non-Functional Requirements (agreed)

- Scale (MVP target): Medium — ~10–30 accounting firms, ~1–3k client companies, ~1–3k reps; pronounced
  upload concurrency around month-/quarter-end.
- Hosting: Java/Spring backend + worker always-on on Hetzner (cheap EU); Supabase (EU/Frankfurt) for Postgres + Auth + Storage + pgvector; React frontend on Vercel/Cloudflare. Backend intentionally not serverless (long-running workers). Supabase Schrems II exposure accepted for MVP.
- Availability: business-hours focus; brief off-hours maintenance windows acceptable in MVP.
- Security: RBAC + multi-tenant isolation (row-level security); TLS; sensitive PII encrypted at rest;
  TOTP MFA for staff; Google OAuth; full audit.
- Privacy/Compliance: GDPR; retention-aware erasure reconciled with Romanian accounting-records
  retention obligations.
- Environments: dev / staging / production with CI/CD.
- Monitoring: health, errors, extraction + notification delivery metrics; alerting; retained logs.
- Data management: daily backups + point-in-time recovery; migration = manual/minimal (spreadsheet
  import of companies; no full historical migration in MVP).
- Integrations (MVP): transactional email provider; Gmail/M365 OAuth (email agent); Document-AI/OCR
  service (receipts); Google OAuth (login). Deferred: WhatsApp Business API; e-Factura/ANAF SPV.
- App UI: bilingual Romanian + English. Planning deliverables: English.
- Tech stack: Java/Spring Boot backend + async worker; React + Vite (PWA) frontend; Supabase Auth
  (email+password, Google OAuth, free TOTP MFA) with Spring validating Supabase JWTs; managed
  Document-AI for receipt OCR; Redis or Postgres pgmq for the job queue.

## 5. Phasing

- PoC: de-risk extraction + reconciliation on real samples — ANAF declaration extraction, bank-statement
  PDF parsing, receipt OCR, missing-doc reconciliation, state-payment email auto-fill + minimal auth/
  company setup to support it.
- MVP: MOD-01…12 in full + bounded chatbot (MOD-13) + responsive web/PWA (MOD-14); multi-tenant.
- Future: see module breakdown Future list.

## 6. Diagrams to Include in Requirements Doc

- Domain model (business entities & relationships).
- Process flow: monthly document upload → extraction → reconciliation → completeness.
- Process flow: fiscal declaration upload → extraction → verification → state-payment email.
- State diagram: per-company monthly status (Empty → Partial → Complete → Reported → Sent).

## 7. Open Items / Risks

- Sample documents (ANAF declaration PDF, bank statement PDF, sample invoices + receipt photos) to be
  provided to validate extraction assumptions before estimation.
- Receipt OCR accuracy target + acceptable human-review rate — to validate in PoC.
- Chatbot broader-Q&A grounding/guardrails — PoC/spike.
- Payroll "local directory" idea resolved to manual upload for MVP (sync/integration is Future).
- Exact Romanian accounting-records retention period to confirm with the firm for the erasure policy.
- Email-agent company-mapping rules (sender → company) and triage workflow detail.
- Month-end concurrency profile to confirm sizing.
