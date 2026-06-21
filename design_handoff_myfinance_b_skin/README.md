# Handoff: MyFinance — "Console" (B) redesign

## Overview
A visual + interaction redesign of the MyFinance accounting-firm portal, replacing the dated teal SaaS look with a dense, data-first **"Console"** aesthetic: a dark chrome shell (sidebar + topbar) wrapping light, hairline-ruled content, tabular mono numerals for all money/IDs, and per-company drill-down workflows.

This first drop covers the **shared shell + design tokens**, **Statements & invoices**, and **Taxes & payments**. The remaining modules (Company detail, Payroll, Reports, Representative PWA, Login) will follow as separate drops.

## About the Design Files
The files in `designs/` are **design references created in HTML** — interactive prototypes showing the intended look and behavior. They are **not** production code to copy. Your task is to **recreate these designs inside the existing MyFinance React + TypeScript app**, reusing its established patterns (React Query, the `api/*` clients, the existing modal/page structure, CSS variables) — not to ship the HTML.

The prototypes are authored as "Design Components" and load a runtime (`support.js`). Open them in a browser to interact; ignore `support.js` and the `{{ … }}` template syntax — they're prototype plumbing, not part of the design.

### Which file is the source of truth
| File | Use it for |
|---|---|
| `designs/Taxes Payments.dc.html` | **The definitive B-skin reference.** Final tokens, shell, list, workspace, modals, email preview. Match this pixel-for-pixel. |
| `designs/Statements Directions Enriched.dc.html` | Statements look in the B skin — **use the middle "B · Console" column only** (the file also shows discarded A and C variants side-by-side; ignore them). |
| `designs/Statements Reconciliation.dc.html` | Statements **interactions/flow** reference (reconciliation, associate panel, files+upload, notification log). Note: this one is drawn in the lighter "A" palette — copy its *behavior and layout*, but apply the **B tokens** below for color. |

## Fidelity
**High-fidelity.** Colors, typography, spacing, and interactions are final. Recreate pixel-perfectly using the codebase's existing component patterns. The one caveat: where `Statements Reconciliation.dc.html` uses the light "A" palette, translate its colors to the B tokens (sidebar/topbar become dark, accent stays teal).

---

## Design Tokens

### Color
**Chrome (dark shell)**
- Sidebar / topbar background: `#0c1413`
- Sidebar active item bg: `#16211f` with a `2px` inset left bar `#14b8a6`
- Sidebar nav text (idle): `#8aa09c`; active: `#ffffff`; sub-label muted: `#5e716e`
- Topbar text: `#eef4f3`; muted `#6f857f`; control bg `#16211f`
- Brand mark: bg `#14b8a6`, glyph `#06201d`

**Surfaces (light content)**
- Page background: `#fafafa`
- Card background: `#ffffff`; card border: `#e4e7e6`
- Hairline dividers: `#f0f1f0` / `#f1f3f2` / `#f4f5f5`
- Table header row bg: `#f5f6f6` (lists) / `#f7f8f8` (sub-tables); header label text `#8a9794` / `#9aa6a3`
- Active/selected row bg: `#ecf7f5`; active-action tint bg `#dff5f1` border `#b6e7df`

**Text**
- Primary `#16201f`; secondary `#5e716e`; muted `#9aa6a3`; faint/disabled `#bcc6c3`

**Accent (teal)**
- Primary button: bg `#14b8a6`, text `#06201d` (note: dark text on teal, not white)
- Link / icon accent: `#0f766e`
- Teal tint chip: bg `#e6f4f2`, border `#c9e9e3`, text `#0f766e`

**Status**
- Success: text `#166534`, bg `#dcfce7`, border `#bbf7d0`
- Danger: text `#991b1b`, bg `#fee2e2`, border `#fecaca`
- Warning: text `#92400e` (or `#b45309`), bg `#fef3c7`, border `#fde68a`
- Info / "Sent": text `#3730a3`, bg `#e0e7ff`, border `#c7d2fe`
- "Remembered/learned" purple: text `#6d28d9`, bg `#ede9fe`
- Row status dots: red `#dc2626`, orange `#fb923c`, green `#16a34a`

> Map to existing CSS vars: `--primary → #14b8a6`, `--card-bg → #fff`, `--border → #e4e7e6`, `--text-muted → #9aa6a3`. Add a chrome var set (`--chrome-bg #0c1413`, `--chrome-active #16211f`).

### Typography
- Sans (UI): **Hanken Grotesk** — weights 400/500/600/700
- Mono (numbers, CUI, IBAN, dates): **IBM Plex Mono** — 400/500/600, always with `font-variant-numeric: tabular-nums`
- Scale: page title `h2` 21px/700 (letter-spacing −0.01em); section title 13px/700; body 12.5–13px; meta 11–11.5px; table-header 9.5–10px UPPERCASE, letter-spacing 0.06em, weight 700; pill/badge 10–11px/600.

### Radius
Cards 11–14px · rows/cells/buttons 7–9px · pills 6px (square-ish) or 999px (round) · checkboxes 4px.

### Shadow
- Modal: `0 24px 70px rgba(0,0,0,0.34)`
- Card (rare, on overlays): `0 1px 3px rgba(0,0,0,0.05), 0 14px 40px rgba(0,0,0,0.06)`
- Accent panel (compose): `0 6px 22px rgba(20,184,166,0.10)`

### Spacing & metrics
Sidebar width **210px** · topbar height **54px** · page padding 22–24px · card padding 12–16px · table row padding `9–11px 16px` · gaps 8–16px. Icons 14–15px stroke 1.5.

---

## Shared Shell (build first — every screen depends on it)

**Sidebar** (`210px`, `#0c1413`): brand row (teal `M` mark + "MyFinance" / "ContaZone SRL"); nav items (Dashboard, Statements & invoices, Taxes & payments, Payroll, Reports, divider, Companies). Each item: 15px line icon + 13px label, idle `#8aa09c`. Active item: white text, bg `#16211f`, inset `2px` teal left bar, icon stroked `#2dd4bf`. Footer: 24px avatar + name/role.

**Topbar** (`54px`, `#0c1413`): left breadcrumb (`Module / context`, current segment in `#9fb3af`); right a month stepper (`‹  March 2026  ›` on `#16211f` pill) and a `RO / EN` toggle.

Reusable primitives to extract once: `Card`, `Table`/`Row` (CSS grid rows, not `<table>`), `StatusPill(tone)`, `IconButton`, `Modal` (dark header + scroll body + footer), `Checkbox`, `MoneyCell` (mono tabular).

---

## Screens

### 1. Statements & invoices — hub list
**Files to update:** `pages/Statements.tsx`
**Purpose:** monthly per-company overview; entry point to drill into one company.
**Layout:** page header (title + subtitle "Documents uploaded by representatives · {month}"); a bulk-action bar (appears when ≥1 selected); a card-wrapped grid table.
**Row columns:** checkbox · status dot · Company (name + mono CUI · city) · Bank statement (green "N statements" or red "missing") · Invoices (green count + red "N needs doc") · Completeness pill · **Last sent** (clickable pill "9 Mar · 2 sent · log" or "Never emailed · send") · per-company actions (Upload · Files · Reconcile [disabled w/o statement] · Remind).
**Behavior:** bulk-select → "Send reminder email · N"; row → reconciliation workspace; Last-sent pill → notification history modal.

### 2. Statements — reconciliation workspace
**Files:** `pages/Statements.tsx` (drill view) + `components/ReconModal.tsx` (logic now full-page)
**Layout:** header (back, company, CIF · bank · month); a **statement strip** (multiple banks BRD + BT, "2 statements", cross-check ✓, "N need a document"); body = transactions table (left) + docked right rail.
**Transactions columns:** Date (mono) · Partner + category chip + description · Amount (mono, +green/−ink) · Document (matched filename w/ ✓ + unlink ✕ / "Needs document" + Associate / partial "⚠ X left + Associate") · Decided by (Base rule / Learned / Accountant + purple "remembered") · "Accountant sets" Needs⟷No toggle.
**Right rail:** the **Associate invoice/receipt** panel — for the active transaction, shows "Remaining to allocate", a search, and a list of open invoices; clicking one allocates `min(remaining payment, remaining invoice)` and can stack multiple to cover one transaction; or the default rail = "Documents needed" list + suggested matches.

### 3. Statements — Files & upload modal
**Files:** `components/FilesModal.tsx`
Grouped lists (Bank statements / Invoices & receipts) with type tag, **Duplicate / Wrong-party / date-mismatch / Unclassified** flags, payment-status money button → Payments view, **drag-and-drop upload** with an "extracting…" progress state, and a document preview pane. Empty state when a company has no files (drop-to-upload focus).

### 4. Statements — Associate invoice / Payments
**Files:** `components/LinkInvoiceModal.tsx`, `components/InvoicePaymentsModal.tsx`
Link-invoice = the associate panel above (multi-link, live remaining allocation). Payments = Total / Paid / Remaining / status + applied-payments list + Add payment.

### 5. Taxes & payments — hub list  ★ definitive B reference
**Files:** `pages/TaxPayments.tsx`
**Row columns:** checkbox · Company (name + mono CUI · city) · **D100** · **D112** · **D300** (each a right-aligned mono amount; amber `⚠` when computed ≠ declared, or muted "—") · **To pay** (mono, bold) · **Last sent** (clickable pill → email history; "Never sent · send" when none) · actions (Manage declarations · Tax workspace · Send email).
**Bulk:** checkbox select → top bar "N companies selected · Send email · N" → **Email preview modal** (see Interactions).
**Note:** the company column must have a min width (`minmax(210px, 1.5fr)`) and the table a horizontal scroll, so fixed columns never collapse/overlap at narrow widths.

### 6. Taxes — payment workspace
**Files:** `components/TaxPaymentModal.tsx` (promoted to a full workspace)
Two-column: left = **Declarations** (checkbox-selectable rows: form D100/D112/D300, computed amount + ⚠ mismatch tooltip showing declared, "sent N× · date"; footer "Compose email · N selected"), then an inline **Compose** panel (recipient input + editable body prefilled from the selected declarations) → Send; then the **To pay** breakdown table (Explanation · IBAN mono · Due · Amount + Total row) with an amber **unconfigured-category** warning. Right column = **Email history** (date/time mono, recipient, status SENT/FAILED, Resend; "JUST NOW" badge on a just-sent row).

### 7. Taxes — Declarations modal
**Files:** `components/DeclarationsModal.tsx`
Master–detail: left file list (each declaration shows amount, CIF, a green **"✓ Sent N× · last {date}"** badge or muted "Not sent", and **MISMATCH / OUT OF PERIOD / WRONG PARTY** flags), delete ✕ per file, a **drag-drop upload** zone; right a PDF preview that switches on select.

---

## Interactions & Behavior
- **Notification / email log (shared, both modules):** a per-company history modal — header (name, "N sent · to {email}"), a "Send a new …" bar (Send email now + a disabled **WhatsApp "SOON"** channel), and a timeline (subject, channel, recipient, date+time, status pill Opened/Delivered/Sent). Sending **appends** a dated "JUST NOW" entry; multiple sends stack. The list's "Last sent" pill is the trigger.
- **Email preview before send (Taxes):** single ✉ or bulk "Send email · N" opens a preview modal rendering **one card per company** — recipient, that company's declaration lines, total chip, and the full generated email body — then "Send N emails" dispatches and logs each into that company's history with today's date.
- **Reconciliation associate:** clicking an invoice allocates against the transaction's remaining amount; remaining counts down; invoice leaves the list when exhausted; "needs a document" count decrements as transactions are covered.
- **Reconcile gating:** the per-company Reconcile action is disabled until that company has an uploaded bank statement.
- **Upload:** drag-drop or browse → file appears in an "extracting…" state (progress bar) → resolves to a classified file with flags.

## State Management
Reuse the existing React Query setup and `api/*` clients (`taxes.ts`, `documents.ts`, `bank.ts`, etc.). New/extended client state per screen: selected-company set (bulk), selected-declaration set (compose), active drill company, open modal id, compose draft (recipient+body), per-company notification/email log (the backend already exposes `emails` / `lastSentAt` / `sentCount` — surface them). No new data shapes are required beyond what `api/taxes.ts` and the statements APIs already return.

## Design Tokens — see the "Design Tokens" section above (colors, type, radius, shadow, spacing).

## Assets
No raster assets. All icons are inline single-path SVGs (14–15px, stroke 1.5) — replace with the codebase's existing icon set (Lucide/your kit) matching shape. Fonts: Hanken Grotesk + IBM Plex Mono (Google Fonts) — add to the app's font loading.

## Files
- `designs/Taxes Payments.dc.html` — definitive B-skin reference (shell, taxes list, workspace, declarations modal, email preview, notification log)
- `designs/Statements Directions Enriched.dc.html` — Statements in B (use the **B · Console** column only)
- `designs/Statements Reconciliation.dc.html` — Statements interaction/flow reference (A palette → apply B tokens)
- `designs/support.js` — prototype runtime (ignore; not part of the design)

## Suggested implementation order
1. Shell + tokens (sidebar, topbar, Card/Table/Pill/Modal/IconButton primitives).
2. Statements list → reconciliation workspace → Files/upload → associate/payments.
3. Taxes list → payment workspace → declarations modal → email preview + notification log.
4. (Next drops) Company detail, Payroll, Reports, Representative PWA, Login.
