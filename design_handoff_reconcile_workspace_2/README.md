# Handoff: Full-Screen Reconciliation Workspace

## Overview
Replaces the current modal-based reconciliation flow (`ReconModal` + `LinkInvoiceModal` +
`LinkTransactionModal`) with a **full-screen, per-company workspace** whose primary job is letting the
accountant **map each bank transaction to the invoice(s)/receipt(s) that document it** — including the
hard cases: one invoice paid across several transactions (installments, possibly uploaded in an earlier
month) and one transaction covering several invoices (split).

Instead of opening a company in a modal, selecting a company **replaces the main content frame** (the
left nav/sidebar stays). The screen is two columns — **bank transactions** on the left (the spine that
must be explained) and **invoices & receipts** on the right (the pool to map from) — with an
**on-demand document preview popover**. This is layout option "1a" from the exploration.

This is the approved direction. Two sibling explorations (`1b` three-pane docked preview, `1c` single
ledger with inline drawer) were rejected — ignore them if you find them.

## About the Design Files
The file in this bundle — **`Statements Reconciliation.dc.html`** — is a **design reference created in
HTML**. It is an interactive prototype demonstrating the intended look, layout, and behavior. It is
**not production code to copy**. It is written in a small in-house template runtime (`.dc.html`) that
is irrelevant to your app.

Your task is to **recreate this design inside the existing MyFinance React/Vite frontend**
(`frontend/src`), using its established patterns: React + TypeScript, `@tanstack/react-query` for data,
`react-i18next` for copy, the `Icon` component, the CSS-variable design tokens in `src/index.css`, and
the existing `bank.ts` API client. **Do not introduce new styling systems or hardcode hex values that
already exist as tokens** — map everything to the `--var` names listed under Design Tokens below.

The good news: **almost all backend endpoints already exist** (see API Mapping). The work is primarily
a new page component + routing, reusing the existing query hooks and mutations from `ReconModal`.

## Fidelity
**High-fidelity.** Colors, spacing, typography, and interaction states are intentional and should be
reproduced faithfully — but expressed through the existing tokens, not the prototype's literal hexes.
The prototype uses stand-in fonts (Public Sans / Spline Sans Mono); the real app uses
**Hanken Grotesk** (`--sans`) and **IBM Plex Mono** (`--mono`) — use those.

---

## Routing

**New route**, nested in the staff `FirmLayout` (so the sidebar + topbar persist), added to
`src/App.tsx` alongside `/statements`:

```tsx
<Route path="/statements/:companyId/reconcile" element={<ReconcileWorkspace />} />
```

- Reached from `Statements.tsx` by clicking a **company name** (make it a link) or the existing
  **Reconcile** icon button (`Icon name="reconcile"`) in the row's action group. Both currently call
  `setReconFor(...)` to open the modal — replace with `navigate(\`/statements/${c.id}/reconcile\`)`.
- The current `viewTransactions` icon button is disabled when `!hasBank`. **Keep it enabled** now — the
  workspace has a first-class empty state for companies with no statement (see Screen 1).
- Back navigation: a `‹` button in the workspace header → `navigate("/statements")`.
- The period comes from `usePeriod()` (same as today), not the URL.

Delete/retire after migration: `ReconModal.tsx`, `LinkInvoiceModal.tsx`, `LinkTransactionModal.tsx`.
(`FilesModal` and `DocumentPreviewModal` stay.)

---

## Screens / Views

### Screen 1 — Empty state (no bank statement, no invoices yet)
**Purpose:** The accountant lands here for a company that has nothing uploaded and must be able to
upload the **bank statement itself** and/or invoices right here.

**Layout:** Same shell as the full state (header + two columns). Differences:
- **Header:** back `‹`, company legal name (21px/700), mono submeta line
  (`CIF … · locality · <Month Year>`), and a right-aligned outline button
  **"Request documents from client"**.
- Below the header, a full-width **statement upload prompt** bar: dashed border, `--danger-bg` tint,
  a document icon in a `--danger-bg` chip, title **"No bank statement yet"** (`--danger-fg`), subtext
  "Upload the statement to auto-extract transactions, or send a request to the representative.", and a
  primary **"Upload bank statement"** button (opens a hidden `<input type=file>`).
- **Left column** ("Bank transactions"): centered empty illustration + "No transactions yet" + an
  "Upload statement" button.
- **Right column** ("Invoices & receipts · N"): if no uploads, a centered empty state ("No invoices or
  receipts yet"); otherwise the uploaded rows with a purple **NEW** chip. A persistent **upload
  dropzone** is pinned to the bottom (dashed teal, "Drop or browse to upload an invoice / receipt",
  "PDF, PNG, JPG · auto-extracted on upload").

**Behavior:** Uploading the statement calls `documentsApi.upload(companyId, period, file)` (same
mutation as `Statements.tsx`), then the transactions query refetches and the screen transitions to the
full state (Screen 2). Uploading an invoice also uses `documentsApi.upload`.

---

### Screen 2 — Full reconciliation workspace
**Purpose:** The core task — select a transaction, review suggestions, map invoice(s), handle
partials.

**Layout (top → bottom):**

1. **Header row:** back `‹` · company name + mono submeta · right side shows live counts
   ("N matched" `--ok-fg`, "N partial" `--warn-fg`, "N need doc" `--danger-fg`) + primary
   **"Request from client · N"** button.

2. **Statement strip** (full width, `--surface` card, hairline border): bank glyph, bank code (BRD),
   masked IBAN + statement filename (mono), opening→closing balance (mono), a green
   "N transactions parsed" check, and a right-aligned **"View"** button that previews the statement
   PDF in the popover. If multiple statements, repeat the row (as `ReconModal` does today).

3. **Two columns** (`flex: 1.12` left / `flex: 1` right, `gap: 14px`), each a bordered `--surface`
   card that scrolls internally.

#### Left column — Bank transactions
- **Column header** (`--th-bg` bg): title "Bank transactions" + a segmented **filter toggle**:
  **All / Unmapped**. "Unmapped" shows only transactions that still need a document (i.e. `requiresDocument && !fullyAllocated`, including partials). Empty result → "No unmapped transactions — everything is reconciled."
- **Transaction rows** (clickable; clicking selects the transaction and drives the right column). Each row:
  - Left border 3px accent: teal (`--primary`) when **selected**; faint green when fully matched; faint red when it needs a doc; transparent otherwise.
  - Top line: **partner name** (600) + a small category chip (`--th-bg`), and right-aligned **amount** (mono, tabular; debit → `--text` with `−`, credit → `#15803d` with `+`).
  - Second line: mono `date · description`.
  - Status area (below):
    - **Fully matched:** one green pill row per matched invoice — check, filename (green, ellipsized), allocated amount (mono), and an **"unmap"** link. Background `--ok-bg`-tint, `--ok-bd` border.
    - **Partial:** the matched pill row(s) **plus** an amber line "⚠ `<remaining>` still unallocated — pick more →".
    - **Needs a document:** a dot + label. When selected, label becomes amber "Matching now — pick a document →"; otherwise red "Needs a document".
    - **Not needed:** grey "Not needed · `<reason>`".

#### Right column — Invoices & receipts
- **Column header** (`--th-bg`): title "Invoices & receipts · `<total>`" + a segmented filter **All / Unmapped** (Unmapped = `remaining > 0`). Below it, a **search input** (file / supplier / amount).
- **Body (scrolls):**
  - **Context card** (only when a transaction is selected). This card is the heart of the redesign and is **contextual to the selected transaction's state**:
    - If the transaction has mapped docs → a green **"Mapped to this transaction"** section listing each mapped invoice (check, filename, allocated amount, preview eye, unmap link).
    - If the transaction still has a remaining/unmatched amount → a **"Suggested · …"** section (`--info-bg` tint) listing ranked suggestions. For a partial, the heading reads "Suggested · remaining `<amount>`" and suggestions target the *outstanding* amount only. Each suggestion: a badge (**EXACT** `--info-fg` bg / **SUPPLIER** teal bg), title (+ "＋N more" for multi-invoice combos), a mono sub-line showing the amounts (`a + b = total`), a preview eye, and a primary **"Accept"** button.
    - No suggestions but amount remaining → "No confident match — pick from the list below, or request the document."
    - Fully reconciled → green "Fully reconciled." Not-needed → grey "No document needed for this transaction."
  - When **no** transaction is selected → a dashed placeholder: "Select a bank transaction on the left to see suggested documents to map." (Suggestions must **not** show until a transaction is selected.)
  - **Invoice pool**, grouped by month with a header rule per group: **"This month · `<Month>`"** first, then **"Other months"** (each group shows its count). Reuse the month-bucketing logic already in `LinkInvoiceModal` (current / prev / before / older-than-3). Each invoice row:
    - A **checkbox** (multi-select) on the left, unless the invoice is fully mapped (then show a green **"mapped"** chip and no checkbox; row is dimmed).
    - Filename (600, ellipsized) + optional **DUP** chip (`inv.duplicate`); mono `supplier · date`.
    - Right: **remaining** amount (mono; `--info-fg` when it exactly equals the selected txn's remaining) + label ("remaining" / "mapped").
    - A **preview eye** (opens the popover; must `stopPropagation` so it doesn't toggle selection).
    - Selected rows get a teal border + `--row-active` tint; suggested rows get an `--info` border + tint.
- **Map bar** (appears when ≥1 invoice is checked, pinned above the dropzone): "Map **N** selected → `<txn date · partner>`" + **Clear** + primary **"Map · N"**.
- **Upload dropzone** pinned at the very bottom (same as empty state) — always in reach.

---

### Overlay — Document preview popover (option 1a)
**Purpose:** Inspect a document without leaving the workspace.
**Behavior:** A **floating popover** (not a full modal) anchored top-right, with a dark PDF-viewer title
bar (filename, page count, ✕), the rendered document, and — for an invoice, when a transaction is
selected and both have remaining — a primary **"Map to `<partner>`"** action + Close. Clicking the
scrim closes it. In the real app, **reuse `DocumentPreviewModal`** for the actual document rendering
(it already takes `companyId`, `documentId`, `filename`); the "map from preview" action calls the same
match mutation. If a lighter popover presentation is desired, it's optional polish — functionally the
existing preview modal is acceptable.

---

## Interactions & Behavior

- **Select a transaction:** click a left row → it becomes selected (teal spine). Right column then
  shows the contextual card (mapped docs and/or suggestions) + the pool. Clicking the selected row
  again deselects. Selecting clears any checked invoices.
- **Accept a suggestion:** applies all links in that suggestion via the match mutation, auto-allocating
  the maximum (see allocation rule). This is a **direct action — no confirm step**; unmapping is cheap.
- **Manual multi-map:** check one or more invoices → "Map · N" allocates each to the selected
  transaction in order (auto-max), then clears the selection.
- **Auto-allocation rule:** every link allocates `min(transaction remaining, invoice remaining)`. A
  transaction can be covered by several invoices; an invoice can be split across several transactions;
  an invoice keeps its remaining balance and stays in the pool until fully allocated (so a Dec invoice
  can be settled by a Feb installment). This matches the existing backend `match` semantics.
- **Unmap:** the "unmap" link on any mapped row calls `bankApi.unmatch`, returning the amount to both
  sides. Counts update live.
- **Requirement toggle (Needs / No):** the accountant can still mark a transaction as needing / not
  needing a document via `bankApi.setRequirement`. In the prototype this lives inline; you may surface
  it in the row's status area or a small control — keep the capability.
- **Filters:** the two segmented toggles are independent client-side view filters over the already-
  fetched lists.
- **Empty → full transition:** happens automatically when the transactions query returns rows after a
  statement upload.

## State Management
Local component state (all else is server state via react-query):
- `selectedTxnId: string | null` — drives the right column; `null` shows the placeholder.
- `checkedInvoiceIds: Set<string>` — multi-select for manual mapping; cleared on selection change / after map.
- `txnFilter: "all" | "unmapped"`, `invFilter: "all" | "unmapped"`.
- `search: string` — invoice pool filter.
- `preview: { documentId; filename } | null`.

Server state / queries (reuse the exact hooks from `ReconModal`):
- `bankApi.statements(companyId, period)` — statement strip / empty-state detection.
- `bankApi.transactions(companyId, period)` → `BankTransaction[]` — the left column. Already includes
  `matched`, `matchedInvoices[]` (with `allocatedAmount`, `filename`, `documentId`), `remainingAmount`,
  `fullyAllocated`, `requiresDocument`, `category`, `decisionSource`, `reason`.
- `invoicesApi.open(companyId, period)` → `OpenInvoice[]` — the right-column pool. Already includes
  `remaining`, `periodMonth` (for month grouping), `duplicate`, `wrongParty`, `paidAmount`.
- `reconciliationApi.suggestions(companyId, period)` → `MatchSuggestion[]` — the suggestion engine
  (**backend service already exists**), returns `EXACT | SPLIT | INSTALLMENT` with `links[]`.

Mutations (reuse from `ReconModal`, same `invalidateRecon` set of query keys):
- `bankApi.match(companyId, txnId, invoiceId, amount?)`
- `bankApi.unmatch(companyId, txnId, invoiceId)`
- `bankApi.setRequirement(companyId, txnId, requiresDocument)`
- `applySuggestion` — iterate `s.links` calling `bankApi.match` for each (as `ReconModal` does today).
- `documentsApi.upload(companyId, period, file)` — statement + invoice upload.

## API Mapping — what exists vs. what's needed
**Requirement #3 was "suggestions from a backend service" — it already exists** as
`reconciliationApi.suggestions()`. One gap to close:

- The suggestions endpoint currently returns **company-wide** proposals, not proposals **for the
  selected transaction**. Two options:
  1. **Preferred (backend):** add an optional `?transactionId=` query param to
     `GET /companies/{id}/match-suggestions` so it returns ranked candidates (EXACT-amount first, then
     SUPPLIER-name, then date proximity) for one transaction, and add a `SUPPLIER` kind. This keeps
     ranking server-side.
  2. **Client-side interim:** filter the existing `MatchSuggestion[]` to those whose `links[0].transactionId === selectedTxnId`, and additionally derive simple same-supplier / exact-remaining
     candidates from `invoicesApi.open()` on the client. Acceptable for a first cut.
- Everything else (transactions with allocation state, open-invoice pool with month + remaining,
  match/unmatch, requirement, uploads, preview) is **already provided** — no new endpoints required.

## Design Tokens (use these `src/index.css` variables — do NOT hardcode)
- **Accent/teal:** `--primary #14b8a6`, `--primary-ink #06201d`, `--primary-dark #0f766e`,
  `--primary-light #ecf7f5`, `--row-active #ecf7f5`, teal chip `--teal-chip-bg/-bd/-fg`.
- **Surfaces:** `--bg #fafafa`, `--surface #fff`, `--border #e4e7e6`, `--hair #f1f3f2`,
  `--th-bg #f5f6f6`.
- **Text:** `--text #16201f`, `--text-secondary`, `--text-muted #9aa6a3`, `--text-faint`.
- **Status:** ok `--ok-fg/#166534 --ok-bg/#dcfce7 --ok-bd/#bbf7d0`; danger
  `--danger-fg/#991b1b --danger-bg/#fee2e2 --danger-bd/#fecaca`; warn
  `--warn-fg/#92400e --warn-bg/#fef3c7 --warn-bd/#fde68a`; info (suggestions)
  `--info-fg/#3730a3 --info-bg/#e0e7ff --info-bd/#c7d2fe`; purple (NEW/remembered)
  `--purple-fg/#6d28d9 --purple-bg/#ede9fe`; dots `--dot-red/#dc2626 --dot-green/#16a34a`.
  Positive amounts use `#15803d` (as in `ReconModal`).
- **Type:** `--sans "Hanken Grotesk"`, `--mono "IBM Plex Mono"` (use `.mono` class for money / IBAN /
  dates / amounts). Base 13px. Header 21px/700. Amounts `font-variant-numeric: tabular-nums`.
- **Radius:** `--radius 12px` for cards; 8–9px for rows/inputs/buttons; 999px for pills/chips.
- **Reused classes:** `.card`, `.pill .round .ok/.danger/.warn/.info/.muted/.teal/.purple`,
  `button` / `button.primary`. The `Icon` component (`folder`, `upload`, `reconcile`, `mail`, plus an
  eye — add one if missing).

## Assets
No new image assets. Icons come from the existing `Icon` component (SVG). Document rendering is handled
by `DocumentPreviewModal`. The prototype's inline SVGs (eye, check, upload, document) map to `Icon`
entries — add an `eye` icon to `Icon.tsx` if one isn't present.

## Files
- **`Statements Reconciliation.dc.html`** — the hi-fi interactive prototype (this bundle). Open it in a
  browser to see every state: empty, no-selection, unmatched-selected (suggestions), fully-mapped
  selected (mapped card), partial-selected (mapped card **+** suggestion for the remainder), the
  filters, multi-select map bar, and the preview popover.
- Target files in `frontend/src` to create/change:
  - **Create** `pages/ReconcileWorkspace.tsx` (the new full-screen page).
  - **Edit** `App.tsx` (add the route), `pages/Statements.tsx` (navigate instead of opening modal;
    make company name a link; stop disabling the reconcile button on `!hasBank`).
  - **Reuse** `api/bank.ts`, `api/documents.ts`, `components/DocumentPreviewModal.tsx`,
    `components/Icon.tsx`, `lib/period`, `src/index.css`.
  - **Retire** `components/ReconModal.tsx`, `components/LinkInvoiceModal.tsx`,
    `components/LinkTransactionModal.tsx`.
- New i18n keys: extend the existing `recon.*` namespace for the filters, section headings, empty
  states, and the map bar.
