# MyFinance — Data privacy & EU residency v1

**Status:** Current · **Scope:** where client data physically lives, which data leaves the EU (and when),
and the controls that keep MyFinance inside the EU. Grounded in the code as of this writing (migrations
through V34; receipt-OCR provider switch shipping with this doc).

> **Hard requirement:** client data must not leave the EU. This document is the map of every place data
> rests or transits, the two paths that can exit the EU, and how each is closed.

---

## 1. What data we hold

- **Documents** — bank statements, invoices, fiscal declarations (PDF/XML), payroll files, receipt photos.
  These are the sensitive payload: they carry company financials, fiscal codes (CUI/CIF), IBANs, and — in
  payroll — personal data (names, salaries, CNP where present).
- **Extracted fields** — amounts, dates, supplier/buyer CIF, IBANs, totals (persisted rows, non-authoritative
  until reconciled).
- **Tenant/identity data** — firms, companies, users, roles (auth is Supabase-issued JWTs).
- **Operational data** — jobs/queue, notifications, audit.

Everything tenant-scoped is isolated by PostgreSQL RLS (`ENABLE` + `FORCE`, fail-closed) — a
**confidentiality** control, orthogonal to **residency** (where the bytes physically sit), which is this
doc's subject.

---

## 2. Where each thing lives (residency map)

| Data | System | Where it runs | EU-resident? |
|------|--------|---------------|--------------|
| Application compute (web + worker) | Self-hosted JVM | **Hetzner** (EU: DE/FI) | ✅ Yes, by deployment |
| Relational DB (tenants, users, extracted fields, jobs metadata) | **Supabase Postgres** | Supabase project region | ⚠️ **Only if the project region is EU** — verify |
| Document blobs | **Supabase Storage** (`SupabaseDocumentStorage`) or local FS (`LocalFsDocumentStorage`, dev) | Same Supabase project region / local disk | ⚠️ Same caveat as DB |
| Auth (JWT issuance, JWKS) | Supabase Auth | Supabase project region | ⚠️ Same caveat |
| Cache / job queue | **Redis** | Wherever provisioned | ⚠️ Must be an EU Redis |
| Outbound email | `EmailSender` port — `LoggingEmailSender` today; **SES** is the prod target | Not yet wired | ⚠️ Pick an **EU SES region** (e.g. `eu-central-1`) |
| **Receipt/invoice OCR (vision LLM)** | `ReceiptExtractor` port | **Anthropic API (US)** *or* **AWS Bedrock (EU)** | ⚠️ **Provider-dependent — see §3** |
| Document mirror / ingestion | **Google Drive** | Google Workspace data region | ⚠️ **Not EU-guaranteed unless Workspace EU data regions — see §4** |

**Compute is EU by virtue of the Hetzner deployment.** The residency risk is entirely in the **managed
data services** (Supabase, Redis, SES) and the **two egress paths** below.

---

## 3. OCR / LLM — exactly which documents are sent, and where

This is the most-scrutinised path because it sends **document images to a model provider**.

### 3.1 Extraction is deterministic-first — OCR is the narrow exception

The pipeline parses text-PDFs deterministically with PDFBox (bank statements, e-Factura XML, fiscal
declarations). **No LLM is involved in the overwhelming majority of documents.** The vision LLM fires only
for the residual case, gated in `InvoiceExtractionService`:

```
needOcr = receiptProps.isEnabled() && (supplierMissing || !textReadable)
```

i.e. **only** when the document is an **INVOICE/RECEIPT** whose text layer is unreadable (a photo/scan) or
whose text parse produced no supplier — and only when a provider is configured. When it does fire it sends
just the **rendered first (and, for multi-page invoices, last) page PNG(s)** plus a fixed extraction prompt.
Bank statements, XML declarations, and any text-readable invoice **never** reach the LLM.

If no provider is configured, `NoopReceiptExtractor` runs and the document simply becomes `NEEDS_REVIEW` —
**no image ever leaves the box** (fail-closed, zero cost).

A secondary vision path exists in `OcrReclassifier` (classification fallback). It guards on
`isAnthropic()`, so under `provider=bedrock` it **safely no-ops** — it will not transmit to the US. (Routing
it through Bedrock too is future work; today its EU-safe behaviour is to disable.)

### 3.2 Two providers behind one port

`ReceiptExtractor` has one shared implementation (`ClaudeReceiptExtractor`: prompt + JSON parsing) and two
transports, selected by `myfinance.receipt.provider`:

| provider | Adapter | Endpoint | Residency |
|----------|---------|----------|-----------|
| `anthropic` (default) | `AnthropicReceiptExtractor` | `api.anthropic.com` (configurable via `base-url`) | **US** — dev/local only |
| `bedrock` | `BedrockReceiptExtractor` | **AWS Bedrock** in the configured region | **EU** when region is `eu-*` |

**Production must run `provider=bedrock` in an EU region.** The Bedrock adapter keeps the image inside AWS's
EU infrastructure; the model/inference-profile id and region are env-config (no code change to switch).

### 3.3 Production config (EU-resident OCR)

```
RECEIPT_PROVIDER=bedrock
RECEIPT_MODEL=eu.anthropic.claude-sonnet-4-5-20250929-v1:0   # an EU inference-profile id
RECEIPT_REGION=eu-central-1                                   # or eu-west-1 / another EU region with Bedrock
# credentials via the instance role (AWS default provider chain) — no key in config
```

Notes:
- Use an **EU regional inference profile** (`eu.*`) so requests stay within EU regions.
- Ensure the AWS account has **not** enabled cross-region model calls that would route outside the EU, and
  that **model-invocation logging** (if enabled) writes to an EU bucket.
- `temperature=0`, `max_tokens=1024`; deterministic extraction; the prompt requests JSON-only.

### 3.4 What we do NOT send

We never send whole document sets, payroll files, bank statements, or XML declarations to any LLM. Only the
page image(s) of a photographed/garbled invoice or receipt, and only when deterministic parsing failed.

---

## 4. Google Drive (mirror + ingestion)

The single per-tenant Drive connection both **ingests** (pulls documents the client dropped in Drive) and
**mirrors** (writes uploads back), via a service-account JWT. **Drive is Google Workspace storage, which is
not EU-resident by default.** To keep this path in the EU:

- The connected Workspace must have **Data Regions = Europe** (a Workspace Enterprise control), OR
- Operate in **Supabase-only mode** (no Drive connection) so documents rest only in the EU Supabase project.

This is an **operational/customer configuration** requirement, not something the app can enforce alone —
call it out in onboarding and the DPA.

---

## 5. The residency checklist (what must be true in production)

1. **Supabase project region = EU** (e.g. `eu-central-1` / Frankfurt). Verify in the Supabase dashboard —
   this covers the DB, Storage blobs, and Auth in one setting. *This is the single most important item.*
2. **Redis = EU** instance.
3. **Email = EU SES region** once the SES adapter replaces `LoggingEmailSender`.
4. **OCR = `provider=bedrock`, EU region, EU inference profile** (§3.3). Never ship `provider=anthropic` to
   production.
5. **Google Drive** (if used) = Workspace **EU data regions**, else Supabase-only mode (§4).
6. **DPAs / SCCs** in place with every sub-processor (Supabase, AWS, Google) scoped to EU processing.
7. **No PII in logs** (existing rule) — masking holds regardless of region.

---

## 6. Residual risks / follow-ups

- **Verify Supabase region** — the biggest single unknown; a US/other-region project silently violates the
  requirement for the DB, blobs, and auth at once.
- **`OcrReclassifier` under Bedrock** disables rather than routing through Bedrock — acceptable (fail-safe,
  no US transmission) but means EU deployments lose that classification fallback until it's ported.
- **SES adapter** is not yet built; until then no email is actually sent (logging stub), so no email egress.
- **Bedrock end-to-end** could not be exercised in dev (no AWS Bedrock access here); the adapter is wired and
  compiles, enabled purely via env in production.

---

## 7. Change log

- v1 — initial map. Shipped alongside the Bedrock EU OCR adapter (`BedrockReceiptExtractor`) and the
  `provider`/`base-url`/`region` config switch on `myfinance.receipt`.
