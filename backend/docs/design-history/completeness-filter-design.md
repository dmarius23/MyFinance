# Completeness filter — "show only companies still missing X this month"

**Status:** Phase 1 (Payroll) in progress on `feat/completeness-filter`.
**Goal:** Turn each module list into an actionable worklist — filter to the companies that still owe a
document/declaration for the selected period, so staff can chase reps without eyeballing every row.

## Decisions (agreed with product)

1. **Granularity — simple binary.** One reusable toggle `[ All | Needs attention ]` beside `CompanySearch`
   in every module. Taxe & plăți adds a second control: an `All / D100 / D112 / D300` type picker.
2. **Expected-aware.** "Missing" means *expected but not received* — never flag a company for a document it
   isn't required to file. Blank/null fiscal fields ⇒ **not** flagged (fail-open, never a false accusation).
3. **Cadence — simple (ignored for now).** A required type with **zero uploads this month** is flagged,
   regardless of monthly/quarterly/annual cadence. Accepted trade-off: **D100 and quarterly-VAT D300 will
   read as "perpetually missing"** outside their few filing months. Future refinement (not now): only flag
   in due months (D112 monthly; D300 by `vat_period`; D100 in Apr/Jul/Oct/Jan).
4. **Server-side.** Each module list endpoint gains `?filter=missing` (Taxe also `&declType=`). The
   expectation rules live in one backend helper (`ExpectedDocuments`), unit-tested, reusable by the
   dashboard later. Tenants are small (tens of companies), so filtered paging is computed in-memory over
   the tenant's companies — correct with pagination, no fragile client-side gaps.

## Expectation rules (missing = expected AND zero uploads for the period)

| Module          | Expected when                         | Missing means                                   |
|-----------------|---------------------------------------|-------------------------------------------------|
| Bank statements | every active company                  | no bank statement, or reconciliation ≠ COMPLETE |
| Payroll         | `has_employees = true`                | no PAYROLL document                             |
| Reports         | every active company (monthly)        | no TRIAL_BALANCE document                       |
| Taxe · D112     | `has_employees = true`                | no D112 declaration                             |
| Taxe · D300     | `vat_status = VAT_PAYER`              | no D300 declaration                             |
| Taxe · D100     | `tax_regime ∈ {MICRO, PROFIT}`        | no D100 declaration                             |

Fiscal fields are already normalized in the data (`VAT_PAYER`/`NON_VAT_PAYER`, `MONTHLY`/`QUARTERLY`,
`MICRO`/`PROFIT`, boolean `has_employees`). `ExpectedDocuments` reads them defensively.

## Architecture

- **`ExpectedDocuments`** (`company/application`): pure functions over a `Company`'s profile —
  `owesPayroll(company)`, later `owesBalance`, `owesBankStatement`, `owesDeclaration(company, type)`.
  No DB, no other-module deps beyond the declaration-type enum; fully unit-testable.
- **Per-module paginated endpoint** returning self-contained rows (company identity + status), mirroring the
  existing `/api/v1/tax-payments/page`. Two params: `q` (fuzzy company search, already supported by
  `CompanyRepository.search`) and `filter` (`all` | `missing`); Taxe adds `declType`.
  - `filter=all` → `companies.search(q, pageable).map(buildRow)` (DB-paged, unchanged behaviour).
  - `filter=missing` → load the tenant's companies matching `q`, keep those `ExpectedDocuments` says owe the
    doc **and** have zero uploads this month, name-sort, page in-memory into a `PageImpl`.
- **Shared frontend toggle** `components/CompletenessFilter.tsx` — `[ All | Needs attention ]`, emits an
  `"all" | "missing"` value; the page threads it into the list query key and the endpoint call.

## Rollout order

1. **Payroll** — reference slice (simplest expectation: one boolean). ← this phase
2. **Reports** — `owesBalance` = active; missing = no trial balance.
3. **Bank statements** — reuse existing `CompanyCompleteness.completeness`; missing = not COMPLETE.
4. **Taxe & plăți** — extend `/tax-payments/page` with `filter` + `declType`; `owesDeclaration(company,type)`.

## Phase 1 — Payroll (this change)

**Backend**
- `ExpectedDocuments.owesPayroll(Company)` = `Boolean.TRUE.equals(company.getHasEmployees())`.
- `PayrollService`: inject `CompanyRepository`; enrich `PayrollRow` with `companyName`, `cui`, `locality`;
  add `listPage(period, q, filter, page, size)`.
- `PayrollController`: `GET /api/v1/payroll/page?period=&q=&filter=&page=&size=` → `PageResponse<PayrollRowResponse>`.
  (The non-paged `GET /api/v1/payroll` stays for any existing callers.)
- **Tests:** `ExpectedDocumentsTest` (unit); `PayrollCompletenessFilterIT` — a company with employees and no
  payroll doc appears under `filter=missing`; a company with a payroll doc, or with `has_employees=false`,
  does not; mandatory cross-tenant isolation (tenant B's missing company never leaks to tenant A).

**Frontend**
- `api/payroll.ts`: `PayrollRow` gains `companyName`/`cui`/`locality`; add
  `listPage(period, q, filter, page, size)`.
- `components/CompletenessFilter.tsx`: reusable `[ All | Needs attention ]` segmented control.
- `Payroll.tsx`: switch the list to an infinite query on `/payroll/page` keyed by `(period, dq, filter)`;
  render rows from the paginated data (drop the company-list + summary merge); add the toggle. Bulk
  select / deep-link focus / WhatsApp keep working off the row's embedded company identity + documents.
- i18n: `filter.all`, `filter.needsAttention` (ro + en).

## Definition of done (per module)

Build green (tsc strict + vite; backend compile); expectation rules unit-tested; the paginated+filtered
endpoint has an IT incl. **cross-tenant isolation**; blank fiscal fields never flag; toggle consistent with
the other modules.
