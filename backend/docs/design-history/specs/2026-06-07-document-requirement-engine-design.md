# Document-Requirement Engine + Reconciliation Screen (Slice D) — Design Spec

**Date:** 2026-06-07
**Module:** `ro.myfinance.extraction` (reconciliation sub-area) + frontend recon screen
**Status:** Approved for planning

## 1. Goal & scope
Decide, per bank transaction, whether it **requires a supporting document** (invoice/receipt) — deterministically, no LLM — let the accountant **override** (and have the system **learn** the decision), surface the **missing-docs** list + **completeness**, and complete the reconciliation screen to match the prototype. Plus **deduplicate** transactions across overlapping/re-uploaded statements.

**In scope**
- Ingestion-time **transaction dedup** (balance-based key, with fallback).
- **Rule engine**: base rules + learned rules; classification runs automatically after a statement is parsed.
- **Accountant override** → upserts a learned `transaction_rule` (applied to future matching transactions).
- **Missing-docs** list + per-company **completeness** (NOT_STARTED / PARTIAL / COMPLETE).
- Recon modal: Document / Reason / Decided-by / "Accountant sets" toggle + red missing panel + "Request these from client" (**preview only**).
- Statements list **Completeness** column becomes real.

**Out of scope (later)**
- **Auto-matching** invoices/receipts ↔ transactions and the green **"Matched ✓"** state → Slice C + matcher. Slice D is forward-compatible: completeness/missing-docs key off `requires_document && matched_document_id == null`, so when the matcher sets `matched_document_id`, rows auto-resolve with no Slice-D change. The re-match-on-upload trigger reuses the existing `DocumentUploadedEvent`.
- **Matching cardinality** (one invoice ↔ many transactions, instalments) → flagged for Slice C; current `matched_document_id` is a single link.
- Real reminder email send → notifications module.

## 2. Transaction dedup (ingestion-time)
When a statement is parsed, before persisting its transactions, skip any that already exist for the company:
- **Key (strong):** `(account_iban, txn_date, amount, balance_after)` — the running balance uniquely identifies a row within an account.
- **Key (fallback, no per-row balance):** `(txn_date, amount, normalized description, ref)`.
- Applied against existing `bank_transaction`s for the company **and** within the current parse batch (intra-batch dupes). Re-uploading the same statement becomes idempotent.
- **Cross-check** still runs on the statement's *full* parsed set (it self-balances); only **persistence** is deduped. `bank_statement.txn_count` = rows actually saved from that statement.
- Note: `bank_transaction` needs `account_iban` available for the key — store it on the row (add column in `V6`) so dedup needn't join to the statement.

## 3. Rule engine (deterministic)
Priority: **learned rule → base rule**; transactions already `ACCOUNTANT_SET` are never reclassified.

**Base rules** (first match wins), evaluated on direction + partner IBAN + partner name + description (diacritics-insensitive):
| # | Condition | requires_document | category |
|---|---|---|---|
| 1 | CREDIT (amount > 0) | false | `INCOME` |
| 2 | partner IBAN matches `^RO\d{2}TREZ` | false | `TAX` |
| 3 | own transfer: partner IBAN == statement account IBAN, or partner name ≈ company legal name | false | `OWN_TRANSFER` |
| 4 | description contains `salariu`/`salary` | false | `SALARY` |
| 5 | description contains `comision`/`fee`, or partner contains `netopia` | false | `FEE` |
| 6 | description contains `leasing` | **true** | `LEASING` |
| 7 | else (DEBIT) | **true** | `SUPPLIER` |

`decision_source` ∈ `SYSTEM_RULE | LEARNED_RULE | ACCOUNTANT_SET`. The **reason** string is derived at read time from `(decision_source, category)` (no stored column); `ACCOUNTANT_SET` uses `override_reason`.

**Classifier is a pure component** (`TransactionClassifier`) taking the txn fields + the applicable learned rules and returning `(requiresDocument, category, decisionSource)` — unit-testable without a DB.

## 4. Learned rules — `transaction_rule` (Flyway `V6`)
```sql
transaction_rule(
  id uuid pk, tenant_id uuid not null, company_id uuid not null,
  match_iban text,            -- partner IBAN (nullable)
  match_desc_norm text not null,  -- normalized description
  requires_document boolean not null,
  created_by uuid, created_at timestamptz not null default now(),
  unique (tenant_id, company_id, match_iban, match_desc_norm)
)
```
- RLS `tenant_isolation` (V3 pattern) + grants. Plus `ALTER TABLE bank_transaction ADD COLUMN account_iban text;` (for dedup, §2).
- A rule matches a txn when `Objects.equals(match_iban, txn.partnerIban)` **and** `match_desc_norm == normalize(txn.description)`.
- `normalize` = lowercase, strip diacritics, collapse whitespace, trim.

## 5. Backend components (`ro.myfinance.extraction`)
- `domain/`: `TransactionRule`; `DocCategory` enum (`INCOME, TAX, OWN_TRANSFER, SALARY, FEE, LEASING, SUPPLIER`).
- `application/`: `TransactionClassifier` (pure rules), `ReconciliationService` (classify a statement's txns; `setRequirement`; completeness summary), `TransactionRuleRepository`.
- Dedup added to `BankStatementExtractionService`; after persisting deduped txns it calls `ReconciliationService.classify(...)`.
- `BankTransactionRepository`: add `findByCompanyId(UUID)` (dedup + completeness).

## 6. API (staff: `TENANT_ADMIN`/`EMPLOYEE`)
- Enrich the existing `GET /companies/{id}/bank-transactions` `TransactionResponse` with: `requiresDocument`, `category`, `decisionSource`, `reason`, `matched` (`matched_document_id != null`).
- `PATCH /companies/{id}/bank-transactions/{txnId}/requirement` `{ requiresDocument, reason? }` → set txn (`ACCOUNTANT_SET` + `override_reason`), upsert learned `transaction_rule`, audit; returns the txn.
- `GET /reconciliation/summary?period=yyyy-MM-dd` → per company `{ companyId, completeness }` where
  `NOT_STARTED` = no statement; `COMPLETE` = statement present AND no `requires_document && !matched` txn; else `PARTIAL`.

## 7. Frontend
- `bankApi`: `setRequirement(companyId, txnId, requiresDocument, reason?)`; `reconciliationApi.summary(period)`. Extend `BankTransaction` type with the new fields.
- **ReconModal** (complete it): columns Document (`Needs doc` ⚠ / `Not needed`; no `Matched` yet), Reason, Decided by (Base rule / Learned / Accountant), **Accountant sets** toggle (Needs doc | No doc → PATCH, optimistic refresh, "remembered" tag). Highlight needs-doc rows. Red **"Documents the representative must provide — N"** panel; **"Request these from client"** → preview list (no send).
- **Statements page**: merge `reconciliation/summary` → real **Completeness** pill (Complete / Partial / Missing).
- i18n RO/EN for the new labels.

## 8. Testing
- `TransactionClassifierTest` (unit, no DB): each base-rule category; learned rule overrides base.
- `ReconciliationServiceIT` (Testcontainers, skips w/o Docker): classification on a parsed statement; **dedup** across two overlapping statements (one row per real txn); accountant override creates a learned rule; a second statement with the same counterparty+description picks up the learned decision; completeness states; cross-tenant isolation.
- Frontend: `npm run lint && npm run build`.

## 9. Build order
1. `V6` migration (`transaction_rule` + `bank_transaction.account_iban`).
2. `DocCategory`, `TransactionRule` entity + repo; `BankTransactionRepository.findByCompanyId`.
3. `TransactionClassifier` (pure) + unit test.
4. Dedup in `BankStatementExtractionService` (set `account_iban`; skip dupes) + classify chaining.
5. `ReconciliationService` (classify, setRequirement, completeness) + `ReconciliationServiceIT`.
6. Enrich `TransactionResponse`; add override + summary endpoints.
7. Frontend API + types + i18n.
8. ReconModal completion (columns, toggle, missing panel, request preview).
9. Statements completeness column.
10. Verify.
