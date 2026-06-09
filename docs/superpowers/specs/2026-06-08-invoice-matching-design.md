# Invoice Matching / Reconciliation (Slice C) — Design Spec

**Date:** 2026-06-08
**Module:** `ro.myfinance.extraction` (invoice + matcher) + frontend recon modal
**Status:** Approved for planning

## 1. Goal & scope
Extract enough from invoice PDFs to match them, **match invoices ↔ needs-doc transactions** (auto 1:1 + manual many-to-many), and reflect matches as **"Matched ✓"** with auto-updating completeness. Matching reuses the existing upload event so it re-runs when an invoice (or statement) is added.

**Rules (from the user):**
- A transaction may only cover an invoice when **`txn_date >= invoice_date`** (no paying before issue).
- **Many-to-many**: one invoice ↔ several transactions (instalments); one transaction ↔ several invoices (rare).

**In scope**
- `invoice` extraction (heuristic text-PDF: supplier IBAN + total + invoice date).
- A **many-to-many** match join table.
- **Auto-match** the clean **1:1** case (IBAN + amount + date rule).
- **Manual link/unlink** supporting full m:n, with the date rule validated.
- Completeness/"matched" derived from the join table; recon modal shows linked invoices + link/unlink.

**Out of scope (later)**
- Subset-sum **auto** matching of instalments / one-txn-many-invoices (handled manually now).
- **Receipt OCR** (Claude vision / Bedrock).
- Full **e-Factura XML** structured parsing (heuristic text-PDF now; XML is a fast-follow).
- Partial-amount allocation tracking (a link is whole-invoice ↔ whole-transaction); near-match suggestions; FX.

## 2. Data (Flyway `V7`)
```sql
CREATE TABLE invoice (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenant(id),
    document_id       uuid NOT NULL UNIQUE REFERENCES document(id) ON DELETE CASCADE,
    company_id        uuid NOT NULL REFERENCES company(id),
    period_month      date NOT NULL,
    supplier_name     text,
    supplier_iban     text,
    total_amount      numeric(15,2),
    invoice_date      date,
    original_filename text,
    status            text NOT NULL,          -- EXTRACTED | NEEDS_REVIEW
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE transaction_invoice_match (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    transaction_id uuid NOT NULL REFERENCES bank_transaction(id) ON DELETE CASCADE,
    invoice_id     uuid NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,
    source         text NOT NULL,             -- AUTO | MANUAL
    created_by     uuid,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_txn_invoice UNIQUE (transaction_id, invoice_id)
);
CREATE INDEX idx_invoice_company_period ON invoice(tenant_id, company_id, period_month);
CREATE INDEX idx_tim_transaction ON transaction_invoice_match(transaction_id);
```
- RLS `tenant_isolation` (V3 pattern) + `myfinance_app` grants on both tables.
- `bank_transaction.matched_document_id` (V5) becomes **legacy/unused** — match state now lives in the join table.

## 3. Backend (`ro.myfinance.extraction`)
- **`InvoiceExtractor` port + `HeuristicInvoiceExtractor`**: from PDF text → `ParsedInvoice(supplierName, supplierIban, totalAmount, invoiceDate)`. Heuristics: supplier IBAN = first `RO\d{2}…` IBAN; total = the money token on/after a `total`/`total de plat` label (fallback: largest money token); date = first `dd.MM.yyyy`/`dd/MM/yyyy`. Number parsing reuses RO/EN logic. Never throws.
- **`InvoiceExtractionService.process(documentId, companyId, periodMonth, filename, bytes)`**: extract text (PDFBox) → `InvoiceExtractor` → persist `invoice` (status `EXTRACTED` if IBAN+total found else `NEEDS_REVIEW`) → `reconciliation.matchPeriod(companyId, periodMonth)`.
- **`ReconciliationService` (extend):**
  - `matchPeriod(companyId, periodMonth)` — **auto 1:1**: for each `requires_document` transaction with no match link, find an unused `invoice` in the period where `supplier_iban == partner_iban` (both non-null) AND `|total_amount| == |amount|` (±0.01) AND `txn_date >= invoice_date`; create an `AUTO` `transaction_invoice_match`. Idempotent; each invoice and each txn used at most once per run.
  - `link(companyId, txnId, invoiceId)` — manual; validate both belong to the company and `txn_date >= invoice_date` (else `IllegalArgumentException`→400); upsert a `MANUAL` match; audited. Supports m:n (no 1:1 restriction).
  - `unlink(companyId, txnId, invoiceId)` — delete the match; audited.
  - `transactionsWithMatches(companyId, periodMonth)` — read model: each txn + its linked invoices (`invoiceId, documentId, filename, totalAmount, invoiceDate, supplierName`).
  - `completenessSummary` — update: a `requires_document` txn is **resolved** when it has ≥1 match link; `COMPLETE` = statement present AND no unresolved `requires_document` txn.
- **`InvoiceRepository`, `TransactionInvoiceMatchRepository`** (`findByTransactionIdIn`, `findByTransactionId`, `existsByTransactionIdAndInvoiceId`, `deleteByTransactionIdAndInvoiceId`).
- **Triggers:** `DocumentUploadedEvent` gains a `filename` field. `StatementExtractionListener` (AFTER_COMMIT) routes by type: `BANK_STATEMENT` → existing parse/classify, then `matchPeriod`; `INVOICE` → `InvoiceExtractionService.process`. Both wrapped in try/catch (never break the upload).

## 4. API (staff)
- `GET /companies/{id}/bank-transactions?period=…` — enriched: each txn gains `matchedInvoices: [{invoiceId, documentId, filename, totalAmount, invoiceDate, supplierName}]` and `matched` (≥1 link). (Now served via `ReconciliationService.transactionsWithMatches`.)
- `GET /companies/{id}/invoices?period=…` — invoices for the period `{id, documentId, filename, supplierName, supplierIban, totalAmount, invoiceDate, status}` (candidates for manual linking).
- `POST /companies/{id}/bank-transactions/{txnId}/matches` `{ invoiceId }` — manual link (date rule enforced).
- `DELETE /companies/{id}/bank-transactions/{txnId}/matches/{invoiceId}` — unlink.

## 5. Frontend (recon modal)
- **Document column**: when a txn has links → green **"Matched ✓"** + the linked invoice filename(s) (unlink ✕ per link). When `requires_document` and unlinked → a **Link** button → small picker listing the period's invoices **with `invoice_date <= txn_date`** (the date rule) → `POST matches`. The Needs-doc/No-doc toggle stays.
- Missing-docs panel + Completeness pill auto-update (now from links).
- `bankApi`: extend `BankTransaction` with `matched` + `matchedInvoices`; add `match`/`unmatch`; add `invoicesApi.list`.
- i18n RO/EN for matched / link / unlink / "issued".

## 6. Testing
- `HeuristicInvoiceExtractorTest` (synthetic invoice text → IBAN/total/date; RO and EN amounts).
- Extend `ReconciliationServiceIT`: statement with a supplier debit (needs doc) + upload a matching invoice (date ≤ txn) → **auto-matched**, completeness `COMPLETE`; an invoice dated **after** the txn → **not** auto-matched; manual `link` two invoices to one txn (m:n) and one invoice to two txns; `link` with `txn_date < invoice_date` → rejected; `unlink`; cross-tenant isolation.
- FE: `npm run lint && npm run build`.

## 7. Build order
1. `V7` migration (invoice + match tables).
2. `Invoice` + `TransactionInvoiceMatch` entities + repositories.
3. `InvoiceExtractor` port + `HeuristicInvoiceExtractor` + unit test.
4. `DocumentUploadedEvent.filename`; `InvoiceExtractionService`; extend `StatementExtractionListener` (route INVOICE); `BankStatementExtractionService` calls `matchPeriod` after classify.
5. `ReconciliationService`: `matchPeriod`, `link`, `unlink`, `transactionsWithMatches`, completeness-from-links (+ extend IT).
6. API: enriched transactions endpoint, invoices list, match/unmatch endpoints + DTOs.
7. Frontend API + types + i18n.
8. Recon modal: matched display + link picker + unlink.
9. Verify (backend tests, FE lint+build).
