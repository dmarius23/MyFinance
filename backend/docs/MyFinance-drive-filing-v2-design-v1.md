# MyFinance — Google Drive Filing v2 (design)

Status: **approved-to-implement** · Author: engineering · Supersedes the `DriveDocLayout` mirror layout in
`MyFinance-folder-ingestion-design-v1.md` (MOD-15). This document is the source of truth for how the app
files uploaded documents into a tenant's Google Drive.

## 1. Why this change

v1 wrote every uploaded document into a single app-owned tree
`<root>/<Company>/<YYYY>/<MM>/<type>` (`DriveDocLayout`). Real accounting firms already keep a **specific,
human-curated Drive structure** that they and the client representatives work in daily. The app must file
into **that** structure, not a parallel one. Two consequences:

- Filing now spans **two separate shared drives** (declarations vs. client accounting).
- Placement depends on **extracted metadata** (declaration obligation, invoice direction), not just the
  coarse `Document.type` — so the moment we mirror shifts per type.

## 2. Target structure (per tenant)

### Drive 1 — "Declaratii" (declarations + payrolls + reports)
Settings hold the **all-years parent** folder id; the app finds/creates `Declaratii {year}` under it.

```
<all-years root>/
└── Declaratii {year}/                              (auto-ensured per year)
    ├── {M}. {LunăRo} {year}/                       e.g. "6. Iunie 2026"   (M = month number, no zero-pad)
    │   ├── State de plata/                          ← PAYROLL (fluturaș, pontaj, state de plată)
    │   ├── D112/                                    ← DECLARATION type D112
    │   ├── D300/                                    ← DECLARATION type D300
    │   ├── D100 Chirii/                             ← D100, dominant obligation cod 628
    │   ├── D100 Dividende/                          ← D100, dominant obligation cod 604
    │   └── D100 Profit sau Micro/                   ← D100, dominant obligation cod 103 or 121
    └── Bilant Interimar T{q} an {year}/             (q = trimester 1..4)
        └── {Company legal name}/                    ← TRIAL_BALANCE (bilanț/balanță)
```

Notes:
- Payroll + declaration folders hold **all companies together** (distinguished by filename) — **no**
  per-company subfolder. Only the reports (bilanț) path is per-company.
- Month folder label = `{monthNumber}. {RomanianMonthName} {year}`, month number **not** zero-padded,
  month name Title-Case Romanian (Ianuarie…Decembrie).

### Drive 2 — "Folder Contabilitate Clienti" (bank statements + invoices)
A **second** shared drive; settings hold its own root folder id.

```
<contabilitate root>/                                ("Folder Contabilitate Clienti")
└── Contabilitate {Company legal name}/
    ├── Extrase de cont/                             ← BANK_STATEMENT (filename carries month + year)
    ├── Facturi achizitii/                           ← INVOICE, direction RECEIVED
    └── Facturi emise/                               ← INVOICE, direction ISSUED
```

Notes:
- This drive is **also managed manually by client representatives**; structure is inconsistent and often
  empty. The app creates the folder path on demand and drops the file in the correct leaf. Goal: reps
  upload through the app and we file it here for them.

## 3. Routing table

| Document type | Target drive | Folder segments (under that drive's root) | Placement metadata | Mirror trigger |
|---|---|---|---|---|
| PAYROLL | Declaratii | `Declaratii {y}` / `{m}. {LunăRo} {y}` / `State de plata` | period only | on upload |
| DECLARATION | Declaratii | `Declaratii {y}` / `{m}. {LunăRo} {y}` / `{declFolder}` | `TaxDeclaration.type` + dominant `obligations.cod` | **after extraction** |
| TRIAL_BALANCE | Declaratii | `Declaratii {y}` / `Bilant Interimar T{q} an {y}` / `{Company}` | period → trimester | on upload |
| BANK_STATEMENT | Accounting | `Contabilitate {Company}` / `Extrase de cont` | company only | on upload |
| INVOICE | Accounting | `Contabilitate {Company}` / `{Facturi achizitii \| Facturi emise}` | `invoice_direction` | **after e-Factura parse** |
| RECEIPT | — | not mirrored (Supabase-only) | — | — |
| UNCLASSIFIED | — | not mirrored until typed | — | — |

`declFolder`:
- `D112` → `D112`; `D300` → `D300`
- `D100` → `D100 ` + label of the **dominant** obligation (largest amount):
  `628→Chirii`, `604→Dividende`, `103→Profit sau Micro`, `121→Profit sau Micro`.
  If a D100 has no recognizable obligation → generic `D100` folder + flag for review.

## 4. Data model changes

- **V52 migration**
  - `source_connection.purpose text not null default 'DECLARATIONS'` — values `DECLARATIONS` | `ACCOUNTING`.
    A tenant may have **two** write-enabled connections, one per purpose. (Existing "Declratii" row →
    `DECLARATIONS`, its `root_folder_id` repointed to the all-years parent.)
  - `document.invoice_direction text` (nullable) — `RECEIVED` | `ISSUED` | `UNKNOWN`, set for INVOICE only.
- No secrets added; both are non-sensitive routing metadata. RLS unchanged (both tables already tenant-scoped).

## 5. Layout router (replaces `DriveDocLayout`)

New pure class `DriveFilingRouter` (intake domain), fully unit-testable, no I/O:

```
record Filing(Purpose purpose, List<String> segments) {}
Optional<Filing> route(Document doc, DeclarationContext decl, InvoiceContext inv, Company company)
```

- Returns `empty()` for RECEIPT / UNCLASSIFIED / INVOICE-with-UNKNOWN-direction (→ not mirrored).
- Owns RO month names, trimester math, and the `declFolder` mapping (reuses `ObligationLabels` codes).
- `DriveDocLayout.typeFolder/typeOf` is retired for writing; read-side aliases move to §7.

The mirror listener resolves the **connection whose `purpose` matches** `Filing.purpose`, then calls the
Drive writer with that connection's `root_folder_id`. The writer already creates missing folders
(`ensureFolder`) and tags files with `appProperties.myfinanceDocId`.

## 6. Timing — when each type is mirrored

Because subtype/direction come from async extraction, the single `DocumentUploadedEvent → mirror` becomes
type-specific:

- **PAYROLL / BANK_STATEMENT / TRIAL_BALANCE** — mirror on `DocumentUploadedEvent` (metadata known at upload).
- **DECLARATION** — mirror after the declaration is parsed (obligation known). A re-parse that changes the
  dominant obligation → delete old copy, re-file. Hook: the taxpayments declaration-persisted path.
- **INVOICE** — mirror after e-Factura direction is resolved; `UNKNOWN` → skip (Supabase-only) + flag.

## 7. e-Factura direction extraction (Phase 3)

Parse invoice XML (existing JAXB/Jackson-XML stack) → supplier CUI vs. customer CUI, compare to the
company's CUI:
- company is **buyer** → `RECEIVED` (Facturi achizitii)
- company is **supplier** → `ISSUED` (Facturi emise)
- non-XML / scanned / unparseable → `UNKNOWN` (kept in Supabase, not mirrored, flagged).

## 8. Read-side ingestion (Phase 4)

Reps drop files straight into Drive 2, so ingestion must import the new layout. Extend `FolderMapper`:
- `Contabilitate {Company}/{Extrase de cont|Facturi achizitii|Facturi emise}/file`
  → company (from `Contabilitate …`), type + direction (from leaf folder), period (from filename month+year).
- Drive 1 is app-written (not rep-managed), so read support there is lower priority.

## 9. Orphan / dedup hardening (Phase 5)

Fixes the v1 leak where reclassify/move re-mirrored without cleanup:
- On `changeType` / `movePeriod` / `reclassify` / declaration-reparse: **delete previous `driveFileId`
  before writing**, and **skip** the rewrite when the computed target path is unchanged.

## 10. Backfill (Phase 6, optional)

Either re-file existing documents into the v2 trees (one-off job, delete old copies) or apply v2 to new
uploads only and leave history in place. Default: **v2 going forward**, backfill on request.

## 11. Defaults & edge cases

- Receipts: Supabase-only (no v2 home).
- Unknown invoice direction / unclassified: not mirrored; flagged.
- Missing/blank company legal name: fall back to CUI (as v1 `companyFolder`).
- Best-effort: any Drive failure logs and leaves the document fully usable from Supabase (canonical store).

## 12. Testing (DoD)

- `DriveFilingRouter` unit tests: every type; D100 dominant-obligation selection; trimester + RO month
  labels; empty-routing cases.
- e-Factura direction parse (received/issued/unknown fixtures).
- Read-side `FolderMapper` for Drive 2 paths.
- Cross-tenant isolation test for the new two-connection resolution.
- No-orphan test: reclassify/move deletes the prior mirror copy.

## 13. Build order

Phase 0 (model+settings) → Phase 1 (router + two-target wiring) → Phase 2 (timing) → Phase 3 (invoice
direction) → Phase 4 (read-side) → Phase 5 (orphan/dedup) → Phase 6 (backfill, optional).
