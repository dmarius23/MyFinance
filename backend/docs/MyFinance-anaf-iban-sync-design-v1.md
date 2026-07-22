# MyFinance — ANAF Treasury IBAN Sync (design v1)

**Status:** ready-to-implement brief · **Module:** `settings` (global reference data) · **Depends on:**
`platform_treasury_account` (V35), `PlatformReferenceAdminService`, worker/job queue.

A SUPER_ADMIN–triggered agent that scrapes the official ANAF IBAN catalogue and populates/updates the
global `platform_treasury_account` reference table, so state-payment emails (MOD-07) always point at the
correct, current treasury IBANs — without an accountant hand-copying them.

---

## 1. Goal & scope

- **Trigger:** SUPER_ADMIN, on demand (a button / API call). Not auto-scheduled in v1 (may be added later).
- **Source:** ANAF's public IBAN catalogue,
  `https://www.anaf.ro/anaf/internet/ANAF/asistenta_contribuabili/plata_oblig_fiscale/coduri_iban`.
- **What we extract:** for every county (județ) → every treasury (trezorerie) → its PDF, pull **only**
  the four IBANs we care about (see §4).
- **What we write:** one `platform_treasury_account` row per treasury/residence, effective-dated.
- **Safety gate:** a sync does **not** write live data directly. It produces a reviewable **diff**
  (added / changed / unchanged / failed) that the SUPER_ADMIN approves before it is applied to the live
  reference table. (Golden rule #3 — money-related data is non-authoritative until verified.)
- **Out of scope (v1):** automatic scheduling, non-ANAF sources, per-company IBAN overrides, tax-rate sync.

---

## 2. Source analysis (validated against the live site, 2026-07-21)

Three navigation levels, all deterministic — **no OCR, no LLM**:

1. **Index page** (`.../coduri_iban`) — a WebSphere portal page listing all ~42 counties. Its own links
   are volatile JS-portlet URLs and should **not** be scraped. Every county instead has a **stable static
   page**:
   `https://static.anaf.ro/static/10/Anaf/AsistentaContribuabili_r/iban2014/<County>.htm`
   (e.g. `Alba.htm`, `Arad.htm`, `Arges.htm`, `Bucuresti.htm`). We drive from a **configured county list**
   (the 41 județe + București), not from the portal HTML.

2. **County page** (e.g. `Alba.htm`) — lists that county's treasuries as links to **PDF** files:
   `iban_TREZ001_TREZ002.pdf`, `iban_TREZ001_TREZ003.pdf`, … (`TREZ001` = județean, the rest = operative
   treasuries per town). We parse `<a href="…iban_TREZ*.pdf">` links from this page.

3. **Treasury PDF** (~280 KB, ~64 pages) — one per treasury. Page 1 header names the town, e.g.
   *"Trezorerie operativa Municipiul Alba Iulia"* → **residence** = `Alba Iulia`.

### Extraction is text-based and self-labelling

The budget/account code is **embedded inside the IBAN string itself**, so we never need fragile row/column
alignment — we regex the IBANs and read the code out of each. Validated on the Alba-Iulia PDF (each target
code appears **exactly once**):

| Target code | Meaning | Example IBAN (Alba Iulia) | Match rule (after `RO<2>TREZ<3-digit trez>`) |
|---|---|---|---|
| `5503`      | cont unic — impozit + CAS + CASS | `RO31TREZ0025503XXXXXXXXX` | contains `5503` |
| `20A470300` | CAM | `RO02TREZ00220A470300XXXX` | contains `20A470300` |
| `20A100101` | TVA intern | `RO77TREZ00220A100101XTVA` | contains `20A100101` |
| `20A100102` | TVA extern | `RO24TREZ00220A100102XTVA` | contains `20A100102` |

IBANs are 24 chars; the trailing `XXXX` / `XTVA` are part of the published IBAN. PDFBox (already a
dependency) extracts the text cleanly; Java 21's built-in `java.net.http.HttpClient` fetches everything —
**no new dependencies**.

---

## 3. Data-model changes

Decision (agreed): **5503 fills all three** of impozit/CASS/CAS; **TVA splits** into intern + extern
(new); TVA extern is also **wired into the payment email**.

### 3.1 `platform_treasury_account` — add one column

```
ALTER TABLE platform_treasury_account ADD COLUMN iban_tva_extern varchar;
```

- `iban_tva`        ← `20A100101` (TVA **intern**) — semantics unchanged, now explicitly "intern".
- `iban_tva_extern` ← `20A100102` (TVA **extern**) — **new**.
- `iban_impozite` = `iban_cass` = `iban_cas` ← the **same** `5503` IBAN.
- `iban_cam`       ← `20A470300`.

`PlatformTreasuryAccount.setIbans(...)` and the admin add/update signatures gain the `ibanTvaExtern`
argument; the manual admin screen/API and `PlatformReferenceController` follow.

### 3.2 `TaxCategory` — add `TVA_EXTERN`

`resolveIbans` maps `TVA → iban_tva` and `TVA_EXTERN → iban_tva_extern`. `PaymentCalculator` already groups
by distinct IBAN, so an extern line naturally renders separately with explanation *"TVA extern <month>"*.

> **Dependency / honest limitation.** Today `AnafDeclarationExtractor` emits a **single** TVA obligation
> from D300 (whole net VAT → `TVA`). There is **no extern amount at the source yet**, so an extern payment
> line only appears once a declaration/obligation is classified as extern TVA. v1 therefore: (a) stores
> `iban_tva_extern`, (b) adds the `TVA_EXTERN` category + IBAN resolution + calculator/email rendering, and
> (c) leaves a single, clearly-marked classification hook in the extractor. Populating an extern amount
> from a real D300 field (or a dedicated obligation code) is a follow-up once we confirm the source field.

### 3.3 New tables — staging a sync for review

```
platform_treasury_sync_run
  id uuid pk
  status varchar        -- RUNNING | READY_FOR_REVIEW | APPLIED | FAILED | CANCELLED
  started_at, finished_at, applied_at timestamptz
  started_by uuid       -- the SUPER_ADMIN user
  counties_total, treasuries_total, parsed_ok, parse_failed int
  effective_from date    -- valid_from to stamp on applied rows
  notes text

platform_treasury_sync_item
  id uuid pk
  run_id uuid fk -> platform_treasury_sync_run
  county varchar
  treasury_code varchar  -- e.g. TREZ002
  residence varchar      -- parsed from the PDF header
  source_url varchar     -- the PDF url
  iban_5503, iban_cam, iban_tva_intern, iban_tva_extern varchar  -- scraped values (nullable)
  change varchar         -- ADDED | CHANGED | UNCHANGED | ERROR
  error varchar          -- populated when change = ERROR
```

Both tables are **global** (no `tenant_id`, no RLS), mirroring `platform_treasury_account`; write access is
gated at the web layer with `hasRole('SUPER_ADMIN')`.

**Migration split (one per phase, not one bundle):** `V38__treasury_iban_tva_extern.sql` (Phase 1) adds only
`iban_tva_extern`; the two staging tables land in their own migration in Phase 4 (`V39__anaf_treasury_sync.sql`).

---

## 4. Solution design

### 4.1 Components (hexagonal, in `settings`)

- **Port** `AnafIbanSource` (`settings/application`) — `List<TreasuryIbans> fetchAll(Consumer<Progress>)`;
  keeps HTTP/PDF details out of the service and makes the scraper trivially fakeable in tests.
- **Adapter** `AnafHttpIbanSource` (`settings/adapter/external`) — `java.net.http.HttpClient` +
  PDFBox. Walks county list → county `.htm` → PDF links → downloads → extracts the 4 IBANs via the §2
  regex rules. Reads the residence from the PDF page-1 header.
- **Service** `AnafTreasurySyncService` (`settings/application`) — orchestrates a run: create
  `sync_run` (RUNNING), fetch all, diff each scraped treasury against the current live row for that
  residence, persist `sync_item` rows with `change`, flip run to READY_FOR_REVIEW. Separate `apply(runId)`
  method writes approved rows via the existing `PlatformReferenceAdminService` (add/append effective-dated
  rows), then flips to APPLIED.
- **Worker job** `ANAF_TREASURY_SYNC` — a sync fans out to hundreds of PDFs (~42 counties × up to ~40
  treasuries) and is minutes-long, so it runs in the **worker**, never the request thread. The endpoint
  enqueues the job and returns the `runId`; the UI polls run status.
- **Web** endpoints on a new `AnafTreasurySyncController` (or folded into `PlatformReferenceController`),
  all `@PreAuthorize("hasRole('SUPER_ADMIN')")`:
  - `POST /api/v1/admin/reference/treasury-accounts/sync` → start a run, returns `{runId}`.
  - `GET  …/sync/{runId}` → run status + summary counts.
  - `GET  …/sync/{runId}/items?change=ADDED,CHANGED` → the reviewable diff.
  - `POST …/sync/{runId}/apply` → apply the approved diff to live reference data.
  - `POST …/sync/{runId}/cancel` → discard a run without applying.

### 4.2 Flow

```
SUPER_ADMIN clicks "Sync from ANAF"
  → POST …/sync  → enqueue ANAF_TREASURY_SYNC(runId)  → 202 {runId}
Worker:
  create run(RUNNING)
  for each county in CONFIGURED_COUNTIES:
     GET static <County>.htm  → extract iban_TREZ*.pdf links
     for each treasury pdf:
        download → PDFBox text → regex 4 IBANs → residence from header
        diff vs current live row(residence) → sync_item(ADDED|CHANGED|UNCHANGED|ERROR)
        (polite: small delay / bounded concurrency; retry with backoff; continue on failure)
  run → READY_FOR_REVIEW (counts filled)
SUPER_ADMIN reviews diff (ADDED + CHANGED), clicks Apply
  → POST …/sync/{runId}/apply
     for each ADDED/CHANGED item: append effective-dated platform_treasury_account row
        (impozite=cass=cas=iban_5503, cam=iban_cam, tva=iban_tva_intern, tva_extern=iban_tva_extern)
     run → APPLIED
```

### 4.3 Idempotency & effective dating

- **Diff before write.** For each residence, compare the four scraped IBANs against the currently-effective
  live row. `UNCHANGED` items are skipped on apply (no new row, no churn).
- **Append, never mutate.** Applying a `CHANGED`/`ADDED` item **appends** a new effective-dated row with
  `valid_from = run.effective_from` (default: sync date; SUPER_ADMIN may override before apply). History is
  preserved; the effective-dated read (`greatest valid_from <= period`) picks it up automatically.
- **Re-running** a sync with no upstream changes yields an all-`UNCHANGED` diff and applies nothing.
- **Guard:** a residence already having a live row with the same `valid_from` is reported (not a hard
  failure) — the admin picks a later `effective_from`.

### 4.4 Residence matching

`residence` is parsed from the PDF header (`"Trezorerie operativa (Municipiul|Orasul|Comuna) X"` → `X`) and
normalized (trim, collapse spaces, diacritics preserved to match existing rows). This is the same key
companies resolve against today (`company.locality`), so no company-side change. Residence-name mismatches
between ANAF and existing company localities are an existing concern; the diff surfaces new residences as
`ADDED`, letting the admin spot naming drift.

---

## 5. Resilience & operational rules

- **Politeness:** sequential per county with a small inter-request delay (or bounded concurrency ≤ 4);
  identify with a normal User-Agent; respect timeouts (connect/read).
- **Failure isolation:** a failed county/PDF becomes an `ERROR` `sync_item`; the run continues and still
  reaches READY_FOR_REVIEW. A run only FAILS on an unrecoverable error (e.g. index unreachable).
- **Idempotent job:** keyed by `runId`; safe to retry via the queue's backoff/DLQ.
- **No PII:** IBANs of state treasury accounts are public reference data, not PII — but logs still mask
  nothing sensitive and never dump full page bodies.
- **Observability:** run summary counts (counties, treasuries, parsed_ok, parse_failed) + per-item errors
  are visible to the admin; structured logs per county.

---

## 6. Testing (DoD)

- **Unit — extraction:** feed the real Alba-Iulia PDF (fixture under
  `src/test/resources/fixtures/anaf-iban/`) to the parser; assert the four IBANs + residence exactly.
- **Unit — county page parse:** fixture `Alba.htm`; assert the set of `iban_TREZ*.pdf` links.
- **Unit — diff:** ADDED / CHANGED / UNCHANGED against a fake current-state.
- **Service — apply:** a fake `AnafIbanSource` returns 2 treasuries; assert `platform_treasury_account`
  rows appended with the correct field mapping (5503→3 fields, extern separate) and `valid_from`.
- **Web — authz:** non-SUPER_ADMIN gets 403 on every sync endpoint.
- **Calculator/email:** a `TVA_EXTERN` obligation renders a distinct "TVA extern" line at the extern IBAN.
- No live-network test in CI (scraper hits fixtures via the port); an opt-in manual/integration test may hit
  the real site behind a flag.

---

## 7. Implementation plan (phased)

1. **Schema + model** — `V38` migration (`iban_tva_extern` + staging tables); extend
   `PlatformTreasuryAccount`, `setIbans`, `PlatformReferenceAdminService`, `PlatformReferenceController`
   (+ existing tests) for the new field.
2. **TVA extern category** — add `TaxCategory.TVA_EXTERN`; wire `resolveIbans`, `PaymentCalculator`
   explanation, extractor classification hook; tests.
3. **Scraper port + adapter** — `AnafIbanSource` + `AnafHttpIbanSource` (HTTP + PDFBox + regex),
   configured county list; unit tests on fixtures.
4. **Sync service + staging** — `AnafTreasurySyncService` (run, diff, apply), repositories, run/item
   domain; service tests with a fake source.
5. **Worker job + endpoints** — `ANAF_TREASURY_SYNC` job type + consumer; `AnafTreasurySyncController`
   with the five endpoints; authz tests.
6. **Frontend (separate, later)** — super-admin "Sync from ANAF" button, run-status poll, diff review +
   Apply. (Backend is usable via API without it.)

---

## 8. Open questions / future

- **Scheduling:** add a monthly `@Scheduled` (worker, ShedLock — improvement-plan S5) auto-sync later.
- **Extern TVA source:** confirm the D300 field (or obligation code) that yields the extern amount to fully
  activate the email line.
- **County list source:** hardcoded/config for v1; could later derive from the index page if ANAF stabilizes
  those URLs.
- **Residence canonicalization:** a future normalization map if ANAF vs company-locality naming diverges.
</content>
</invoke>
