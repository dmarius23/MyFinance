# Bank Statement Extraction + Statements Screen (Slice B) — Design Spec

**Date:** 2026-06-03
**Modules:** new **Extraction** module (`ro.myfinance.extraction`, MOD-05) + Intake (MOD-04) + frontend Statements & invoices screen
**Status:** Approved for planning

## 1. Goal & scope

Parse uploaded bank-statement PDFs into transactions and rebuild the **Statements & invoices** screen to match the clickable prototype: a monthly company list with status columns, a files modal with a document viewer, and a **Bank transactions** modal showing parsed transactions.

**In scope**
- `bank_statement` + `bank_transaction` tables (Flyway `V5`). `bank_transaction` is created with the *full* column set (incl. Slice-D's requirement columns, nullable/defaulted) so Slice D adds no ALTER.
- `BankStatementParser` port + a registry/dispatcher (per-bank impls). Synchronous parse on upload via a `DocumentUploadedEvent`.
- Per-statement cross-check (`opening + Σamount == closing`).
- Read endpoints: bank statements + transactions for a company/period; a per-company **documents summary** for the list page.
- Frontend rework of `/statements` to follow the prototype: monthly company list, files modal + viewer, Bank-transactions modal.

**Out of scope (later)**
- The concrete first **bank parser implementation + its fixture test** are *gated on a real sample PDF*. Slice B ships the pipeline + dispatcher + a graceful "unsupported/parse-failed → NEEDS_REVIEW" path + a test stub parser. The first real parser lands when the sample is provided.
- **Completeness** column (true period status) → Slice D. **Reminder email** column + bulk send → notifications module. Both render as `—` placeholders now.
- Needs-doc rules, accountant override, learned rules, missing-docs panel, "Request from client" → Slice D.
- Async worker/queue, idempotent dedup of re-uploaded statements → later hardening.

## 2. Multiple statements per period
A company may have several bank statements in one month (multiple accounts). So:
- Each uploaded `BANK_STATEMENT` document → its own `bank_statement` row (`document_id` unique). Multiple rows per `(company, period)` allowed.
- Period transactions = union across all statements that period. Cross-check is per-statement.
- Re-uploading a file creates another statement (dedup deferred).

## 3. Data model (Flyway `V5__bank_statement.sql`)
```sql
CREATE TABLE bank_statement (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenant(id),
    document_id     uuid NOT NULL UNIQUE REFERENCES document(id) ON DELETE CASCADE,
    company_id      uuid NOT NULL REFERENCES company(id),
    period_month    date NOT NULL,
    bank_code       text,
    account_iban    text,
    opening_balance numeric(15,2),
    closing_balance numeric(15,2),
    status          text NOT NULL,        -- EXTRACTED | NEEDS_REVIEW | FAILED
    cross_check_ok  boolean NOT NULL DEFAULT false,
    txn_count       int NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE bank_transaction (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenant(id),
    company_id          uuid NOT NULL REFERENCES company(id),
    statement_id        uuid NOT NULL REFERENCES bank_statement(id) ON DELETE CASCADE,
    txn_date            date NOT NULL,
    amount              numeric(15,2) NOT NULL,     -- signed: negative = debit
    direction           text NOT NULL,              -- DEBIT | CREDIT
    partner_name        text,
    partner_iban        text,
    description         text,
    ref                 text,
    balance_after       numeric(15,2),
    -- Slice-D columns (created now, unused until D):
    matched_document_id uuid REFERENCES document(id),
    requires_document   boolean NOT NULL DEFAULT false,
    decision_source     text,                       -- SYSTEM_RULE | LEARNED_RULE | ACCOUNTANT_SET
    category            text,
    override_reason     text
);
CREATE INDEX idx_bank_txn_company_period ON bank_transaction(tenant_id, company_id);
CREATE INDEX idx_bank_txn_statement ON bank_transaction(statement_id);
```
- RLS `ENABLE/FORCE` + `tenant_isolation` policy on both tables (V3 pattern). Grants to `myfinance_app`. `ON DELETE CASCADE` from `document` → deleting a statement document removes its `bank_statement` + transactions.

## 4. Backend — extraction module `ro.myfinance.extraction`
```
extraction/
 ├─ domain/        BankStatement, BankTransaction, TxnDirection, StatementStatus
 ├─ application/   BankStatementParser (port), ParsedStatement, ParsedTransaction,
 │                 BankStatementExtractionService, BankStatementParserRegistry
 └─ adapter/
     ├─ persistence/  BankStatementRepository, BankTransactionRepository
     └─ web/          BankStatementController, BankStatementDtos
```
- **Port** `BankStatementParser`: `boolean supports(String pdfText)`, `ParsedStatement parse(byte[] pdf)`.
- `ParsedStatement(bankCode, accountIban, openingBalance, closingBalance, List<ParsedTransaction>)`; `ParsedTransaction(date, amount, partnerName, partnerIban, description, ref, balanceAfter)`.
- `BankStatementParserRegistry`: injected `List<BankStatementParser>`; extracts text once (PDFBox), returns the first parser whose `supports(text)` is true, else empty.
- `BankStatementExtractionService.extract(documentId, companyId, periodMonth, bytes)`:
  - pick parser; if none → persist `bank_statement` `status=NEEDS_REVIEW`, 0 txns; return.
  - parse; on exception → `status=FAILED`.
  - compute `direction` per txn (amount<0 → DEBIT else CREDIT); cross-check `opening + Σamount == closing` (2-dp tolerance) → `cross_check_ok`, `status=EXTRACTED` (still EXTRACTED if cross-check false but parsed — set `NEEDS_REVIEW` when cross-check fails so a human looks). Persist `bank_statement` + `bank_transaction` rows; set `txn_count`.
  - audit `STATEMENT_EXTRACTED`.
- **Trigger:** intake publishes `DocumentUploadedEvent(documentId, companyId, periodMonth, type, bytes)` from `DocumentService.upload` (after save). Extraction's `@EventListener` (synchronous, same request) calls the service when `type == BANK_STATEMENT`. Intake has no compile dependency on extraction.

## 5. Backend — read APIs
- `GET /api/v1/companies/{companyId}/bank-statements?period=yyyy-MM-dd` → list of statement summaries `{id, bankCode, accountIban, openingBalance, closingBalance, status, crossCheckOk, txnCount}`.
- `GET /api/v1/companies/{companyId}/bank-transactions?period=yyyy-MM-dd` → transactions for the period `{id, statementId, txnDate, amount, direction, partnerName, partnerIban, description, balanceAfter}`.
- `GET /api/v1/documents/summary?period=yyyy-MM-dd` (intake) → per company: `{companyId, hasBankStatement, hasInvoiceOrReceipt, fileCount, hasTransactions}` for the caller's tenant (drives the list page). All `@PreAuthorize` staff.

## 6. Frontend — Statements & invoices screen (follow the prototype)
- **`/statements`** page = page-head (crumb + title) + **month bar** (◀ Month YYYY ▶), then a card with a company table:
  `Company` (name + cui·county) · `Bank statement` (Uploaded/Missing pill) · `Invoices / receipts` (Uploaded/Missing) · `Completeness` (`—`, Slice D) · `Reminder` (`—`, notifications) · `Files` (button "N files") · actions `Bank transactions` (disabled if no statement) + `Open`.
  Rows from `companiesApi.list()` joined with `documents/summary`.
- **Files modal** (mirrors `renderFiles`): left = selectable document list (icon, name, source·type, delete) + "Add file" (upload, file-only); right = **viewer** — PDF via `<embed>`/`<iframe>` of the blob URL, image via `<img>`; Download button. Uses Slice A endpoints (`documentsApi.list/upload/remove/download`).
- **Bank transactions modal** (mirrors the left half of `renderRecon`): header "Reconciliation — {company}" + period; per-statement summary line(s) (bank · masked IBAN · opening→closing · ✓/⚠ cross-check); subtitle "Bank statement(s) parsed into N transactions"; table `Date · Partner / description (bold name; desc · IBAN muted) · Amount` (red debit / green credit). Needs-doc columns/panel are Slice D.
- New API clients: `documentsApi.summary(period)`, `bankApi.statements(companyId, period)`, `bankApi.transactions(companyId, period)`.
- i18n RO/EN for the new labels.

## 7. Testing
- `BankStatementExtractionServiceIT` (Testcontainers, skips w/o Docker): register a **test stub parser** (supports a marker, returns a canned `ParsedStatement`); upload a "statement" document → assert `bank_statement` + `bank_transaction` rows created, cross-check ok, period transactions listed; **cross-tenant isolation**. Also: unsupported bank → `NEEDS_REVIEW`, 0 txns.
- Summary endpoint covered via a service/IT assertion (hasBankStatement etc.).
- Frontend: `npm run lint && npm run build`.
- **Gated on sample:** the concrete bank parser + a fixture test asserting exact extracted values from the real (redacted) statement PDF in `backend/src/test/resources/fixtures/`.

## 8. Build order
1. `V5` migration (tables + RLS).
2. Extraction domain entities + repositories.
3. Parser port + records + registry.
4. `DocumentUploadedEvent` (intake) + publish in `DocumentService`; extraction listener + `BankStatementExtractionService` (+ IT with stub parser).
5. Read endpoints (bank statements/transactions) + DTOs.
6. Documents summary endpoint (intake).
7. Frontend API clients + i18n.
8. Frontend Statements list page (monthly list + columns + month bar).
9. Frontend files modal + document viewer.
10. Frontend Bank transactions modal.
11. Verify (backend tests, frontend lint+build).
12. *(when sample provided)* concrete bank parser + fixture test.
