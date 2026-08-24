# Reconcile-by-month + file management (store-then-dedupe-at-view)

## Problem

A bank-statement **file** covers a *date range* (often one month, sometimes several — a real BRD export
spanned 2026-01-05 → 2026-02-24), but reconciliation is **monthly**. Today we force one file → one month
(the dominant transaction month) and dedupe transactions **at storage time** (first file wins, later
overlapping files store zero rows). Consequences:
- A multi-month file hides earlier months (January's transactions sit under "February").
- A separately-uploaded month file dedupes to nothing → invisible, can't be seen or deleted.
- No way for the accountant to see/manage the raw statement files.

## Decisions (locked with the user)

- **Reconcile by transaction date** — a month = all transactions dated in it, from any file.
- **Store-then-dedupe-at-view** — every file keeps its own transaction rows; display + the reconciliation
  engine dedupe by key (IBAN + date + amount + balance) per month, so nothing double-counts.
- **Files list per month** — every file whose transactions fall in the month; multi-month files labelled
  with their real range and shown under each month they touch.
- **Delete = your own uploads only** (`document.uploaded_by == current user`); Drive-synced files are not
  deletable in-app. **Duplicates are allowed**, shown as "duplicate", and deletable by their uploader.
- **Match-breaking delete** → warn, then unmatch.
- **Coverage/gap indicator** per month (continuous balance chain; flag gaps / "no statement for month").

## Model

- `bank_transaction` — unchanged shape, but now **allowed to hold duplicates across statements** (each file
  stores its own rows). Dedupe is a read-time concern.
- `bank_statement` — add `first_txn_date` / `last_txn_date` (the file's real covered range, for the
  file-list label). `period_month` stays (dominant month) for back-compat but is no longer the reconcile
  anchor.
- Invoice **matches** stay on a specific `bank_transaction` row. On file delete, its matched rows are
  unmatched (warn); a surviving duplicate reappears unmatched for re-match. (Auto-re-point = later nicety.)

## Read-time dedupe

The canonical set for a (company, month) = all `bank_transaction` with `txn_date` in the month, grouped by
`(account_iban, txn_date, amount, balance_after)`; keep one canonical row per group (earliest statement).
Both the reconcile **view** and the reconciliation **engine** (classify/match) operate on this set.

## Phases

1. **Backend model + extraction** — stop cross-file storage dedupe; always create a statement for a
   parseable file; store every transaction; record `first/last_txn_date`. Migration `V55`. *(Intermediate:
   the read side still counts duplicates until Phase 2 — do not deploy alone.)*
2. **Reconcile-by-month + dedupe-at-view** — the transactions query + `ReconciliationService` operate on
   the deduped-by-month canonical set. Highest-risk (the live engine) — heavy tests for overlaps /
   multi-month / no-double-count / matches.
3. **Files panel + delete** — per-month file list with status; delete enabled only for own uploads;
   unmatch-on-delete with an impact warning.
4. **Coverage/gap indicator.**

## Migration / backfill

Existing statements were deduped at storage. Re-extraction is idempotent — run the per-company re-extract
(the reconcile "Re-extrage" action) across companies to rebuild each file's full transaction set under the
new rules. No destructive migration.

## Verification

Local, against INNOVATECODE's real BRD data: the Jan+Feb file yields both a January (9) and February (12)
month-view; a separately-uploaded January file is visible and deletable; totals never double-count; deleting
a file unmatches its links and leaves co-covered months intact.
