# Invoice & transaction currency — design (Spec A)

**Date:** 2026-11-08
**Status:** awaiting review
**Scope:** currency as a first-class field on invoices and bank transactions, a matching rule that
never crosses currencies, and currency on screen. Multi-currency statement *ingestion* is Spec B.

## Why

The system has no concept of currency. There is not one `currency` column across all 70 migrations.
`BankTransaction` stores a bare `amount`; `ParsedStatement` carries no currency; `ParsedReceipt` has a
`currency()` field that the Claude extractor fills and **nothing ever reads**. Everything implicitly
assumes RON.

That is now wrong in practice. A client buys abroad — a Bolt ride in Berlin, a German hotel — and those
invoices are in EUR. The concrete failure:

```java
// MatchSuggester:74
if (i.remaining().subtract(t.remaining()).abs().compareTo(TOLERANCE) <= 0) {
```

Amounts are compared with a tolerance and **no currency check**, so a **EUR 59.90** invoice can be
suggested as a match for a **RON 59.90** payment. At roughly 5 RON/EUR those are different payments by
a factor of five. This is a correctness defect, not a missing label: a wrong suggestion that an
accountant accepts silently mis-books a payment.

Found while importing `Ride invoice from Bolt 32.pdf` (EUR 59.90) against a RON statement.

## Decisions already taken

| Question | Decision |
|---|---|
| How far does currency reach? | **Invoices and bank transactions.** Refusing a cross-currency match needs the currency of *both* sides. Receipts are out — the extractor already parses one, wiring it up is separate. |
| Existing rows | **Backfill to RON.** The system has been RON-only in practice. A historical foreign invoice will be mislabelled RON, but that is exactly today's behaviour, so nothing regresses. |
| What happens on a cross-currency pair | **Never auto-suggest.** No FX conversion, no rate source. |
| Non-RON statement sections | Wanted, but **Spec B** — it breaks the one-statement-per-document model (see below). |

## Why Spec B is separate

`bank_statement.document_id` is `UNIQUE`: one document, exactly one statement row, with a single
`opening_balance`, `closing_balance` and `account_iban`. A combined Revolut PDF contains several
sub-statements, one per currency, each with its own IBAN and balances — today `RevolutStatementParser`
keeps only the RON ones and merges them into that single row.

Ingesting non-RON sections therefore means re-keying `bank_statement` per (document, currency), which
reaches the unique constraint, `findByDocumentId`, `deleteByDocumentId`, the "already covered by another
statement" logic, the statements panel and every parser. That is a bigger change than the rest of this
spec combined, it depends on this spec (nothing can tell currencies apart until A lands), and it carries
the real risk. It gets its own spec and its own review.

**Known gap after A ships:** a EUR invoice paid from a EUR Revolut sub-account still reads as unpaid,
because that payment is never ingested. A removes wrong matches and makes currency visible; it does not
make foreign-currency payments reconcilable. B does.

## Data model

One additive migration, `V71__invoice_transaction_currency.sql`:

```sql
ALTER TABLE invoice
    ADD COLUMN currency char(3) NOT NULL DEFAULT 'RON'
        CHECK (currency ~ '^[A-Z]{3}$');

ALTER TABLE bank_transaction
    ADD COLUMN currency char(3) NOT NULL DEFAULT 'RON'
        CHECK (currency ~ '^[A-Z]{3}$');
```

`NOT NULL DEFAULT 'RON'` backfills existing rows in one statement, which is the decision above and keeps
the column free of a null-means-RON rule every future reader would have to know. The `CHECK` keeps it an
ISO-4217-shaped code rather than free text.

No RLS change: these are new columns on existing tenant tables whose policies already cover them, and no
new data-access path is introduced. No new cross-tenant isolation test applies — this is called out
because the backend golden rules require one for *new* access paths.

## Extraction — the invoice's currency

`ParsedInvoice` gains a `currency` field. `HeuristicInvoiceExtractor` determines it, in order:

1. **Beside the chosen total.** `totalAmount()` already selects a specific money token; take the
   currency named on that same line — `Bruttobetrag (EUR): 59.90`, `Total de plata 1.190,00 LEI`.
2. **Document-wide, if unambiguous.** If exactly one distinct currency appears anywhere, use it. This
   catches `Zwischensumme (inkl. MwSt.) 1247.40 EUR` where the label and value are split across lines.
3. **Default `RON`.** A Romanian invoice frequently names no currency at all.

Recognised tokens: ISO codes `RON EUR USD GBP CHF HUF`, the symbols `€ $ £`, and `LEI`/`lei` → `RON`.
Matching is on word boundaries — `RON` must not be found inside a word, and a bare `$` only counts when
adjacent to a number.

Deliberately heuristic and deterministic, consistent with the rest of the extractor. It can be wrong;
being wrong defaults to RON, which is the status quo, and the accountant can see and correct it.

## Extraction — the transaction's currency

`ParsedStatement` gains a `currency` field, defaulting to `RON`. Each persisted transaction is stamped
with its statement's currency by `StatementExtractionListener`.

Three parsers have the currency available, but to different degrees — worth being precise, because only
two of them are a pure pass-through:

| Parser | State today | Work needed |
|---|---|---|
| `Mt940StatementParser` | **already captured**: `BALANCE = ^([CD])(\d{6})([A-Z]{3})([\d.,]+)`, group 3 | pass group 3 through |
| `RevolutStatementParser` | **already read**: the `Currency RON` line, used to *discard* non-RON sections | return it instead of only filtering on it |
| `Camt053StatementParser` | **present in the XML but not read**: the currency is the `Ccy` *attribute* on `<Amt Ccy="RON">`, and the parser reads only the element's text via `firstText(ntry, "Amt")` | read the attribute as well — a small addition, not a pass-through |

Text-PDF bank parsers (BRD, BT, ING, Libra) name no currency reliably and default to `RON`, which is
correct for every statement they handle.

**In Spec A this value is `RON` for every transaction**, because only RON sections are ingested. The field
is introduced now so the matching rule below can be expressed on both sides, and so Spec B has somewhere
to put non-RON values rather than needing a second migration. This is a deliberate no-op-today column, not
an oversight.

## Matching — never across currencies

`MatchSuggester` gains a currency equality guard before the amount comparison, for the single-pair path
and the subset-sum path alike. A pair whose currencies differ is not a candidate at any confidence.

`OpenInvoiceView` and `OpenTxnView` each gain `currency` so the suggester has it without extra queries.

**Manual linking stays allowed.** An accountant who deliberately links a EUR invoice to a RON payment
knows something the system does not (the FX rate on the day). The guard applies to *suggestions*, not to
`ReconciliationService.match()`. The UI shows the two currencies so the choice is informed.

**Expected side effect:** anything currently auto-suggested only because the raw numbers coincide will
stop being suggested. That is the point, but it means an accountant may notice invoices that "used to
match" no longer do. It belongs in the release note.

## API and frontend

`InvoiceResponse` / the open-invoice and open-transaction DTOs expose `currency`. The frontend `Invoice`,
`OpenInvoice` and `BankTransaction` types gain `currency: string`.

There is no shared money formatter today — amounts are formatted inline in at least eight components. Add
`frontend/src/lib/money.ts`:

```ts
export function formatMoney(amount: number | string | null, currency: string, lang: string): string
```

Rules:
- Romanian grouping for `ro`, English for `en`, always two decimals.
- **Show the currency only when it is not RON.** A RON-only client must not have every amount suddenly
  grow a redundant suffix; a EUR amount must never be mistakable for RON.
- Null/unparseable amount renders as `—`, not `NaN`.

Applied first to the surfaces where a mismatch actually costs money — the reconcile workspace, the files
list, the open-invoice and link-transaction pickers, and the payments modal. Remaining inline formatting
is left alone rather than swept up in this change.

Where an invoice and a transaction are shown side by side for linking, both currencies are visible.

## Testing

Backend:
- `HeuristicInvoiceExtractorTest` — the real Bolt text (`Bruttobetrag (EUR)` → `EUR`), the real GINN text
  (split label/value → `EUR`), a Romanian invoice with `LEI` → `RON`, one with no currency → `RON`, and a
  document naming two currencies where the total's line wins.
- A `MatchSuggester` test: same-currency pair still suggested; EUR invoice vs RON transaction of the same
  number **not** suggested; the subset-sum path likewise.
- A migration/entity test that a row written without a currency reads back `RON`.

Frontend:
- `money.test.ts` — RON omits the suffix, EUR shows it, locale grouping for both languages, null → `—`.

## Rollout

The migration is additive with a default, so it is safe to apply before the new code runs, and old code
ignores the column. Backend and frontend ship in the same compose roll, as every deploy here does.

No backfill job and no data repair: the default handles existing rows. Invoices already extracted keep
`RON` until something re-extracts them; `reclassify` for a period will refresh them, and that is the
documented way to pick the currency up on historical documents.

## Out of scope

- Multi-currency statement ingestion — Spec B.
- FX rates and conversion, in any form. No rate source is introduced.
- Receipt currency, though the extractor already parses one.
- Reports, dashboards and the state-payment email, which remain RON-only. Currency does not reach them in
  this spec; mixing currencies into a monthly total needs rates and therefore Spec B or later.
- Sweeping every inline amount formatter in the frontend.
