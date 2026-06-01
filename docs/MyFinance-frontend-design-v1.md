# MyFinance — Frontend & PWA Technical Design (v1)

Companion to the architecture and `MyFinance-backend-design-v1.md`. Build spec for the **web frontend + PWA**. The clickable prototype `MyFinance-prototype.html` is the visual/interaction reference — screens, flows, and behaviors below map to it.

## 1. Stack & ground rules

- **React 18 + Vite + TypeScript**, single-page app, installable **PWA**.
- **Routing:** React Router (one route per page — no stacked sections).
- **State/data:** TanStack Query (server cache) + lightweight client state (Zustand or context). No localStorage for app data beyond auth/session and remembered UI prefs.
- **Styling:** the prototype's design system — teal/green brand (`--primary #0D9488`), Inter/system font, cards, pills, tables. Extract the prototype CSS into a small token set + component library.
- **Auth:** Supabase JS client (email+password, Google OAuth, TOTP MFA enrollment for staff). Supabase issues the JWT; the app sends it as a Bearer token to the Spring API. The app never talks to the DB directly — **API only**.
- **i18n:** bilingual RO/EN via message catalogs (react-i18next), language switch in the top bar. RO default for client-facing.
- **Talks to the API only** (`/api/v1`), via a typed API client generated from the backend OpenAPI.

## 2. App shells (two experiences, one codebase)

- **Firm app (employee/admin):** sidebar + top bar layout (per prototype). Routes guarded by role.
- **Client portal (representative):** mobile-first PWA — phone-style home, camera upload, status, reports. Scoped to the rep's single company.
- **Platform super-admin:** minimal tenant-management screens (MOD-01).

## 3. Routes / pages (firm app) — mapped to prototype

| Route | Page | Notes |
|---|---|---|
| `/login` | Login | email+password + Google; MFA challenge for staff |
| `/dashboard` | Companies overview | month navigator (default last month); **summary tiles** (one per section — Statements & invoices, Taxes & payments, Payroll, Reports — each showing counts of companies All done / Partial / Nothing for the month, click → that section) + a **New companies** tile (onboarded this month: total / in setup / finished); below, the per-company status row; click → company |
| `/companies` | Manage companies | list: name, CUI, type, residence, contact, VAT, responsible, status; add/edit |
| `/companies/:id` | Company detail | month navigator + **5 cards**: General info, Statements & invoices, Taxes & payments, Payroll, Reports (each month-organized) |
| `/statements` | Statements & invoices | month list, per company: bank-statement/invoices/completeness/reminder status; **Files**; **Bank transactions** (disabled when no statement); multi-select → bulk reminder email |
| `/taxes` | Taxes & payments | month list: declarations/amount/Verificat/payment-email; multi-select → bulk payment email; row → declaration detail |
| `/payroll` | Payroll | month list: file/employees/status/emailed; multi-select → bulk payroll email |
| `/reports` | Reports | month list: trial balance/report PDF/status/emailed; multi-select → bulk report email |
| `/notifications` | Notifications | recent activity feed |
| `/tasks` | Tasks | board TODO/IN_PROGRESS/DONE, optional company link |
| `/admin/tenants` | Tenant admin | super-admin only |

Every menu route renders **only its own page**.

## 4. Key interactive components

- **MonthNavigator** — ◀ label ▶, drives the page's data fetch; default = previous month.
- **DashboardTiles** — section summary tiles (docs/taxes/payroll/reports), each with All-done / Partial / Nothing counts for the selected month, a proportional bar, and click-through to the section; plus a New-companies tile (onboarded this month: total / in setup / finished).
- **CompanyStatusTable** — status pills (Complete/Partial/Missing/—), row → company detail.
- **MultiSelectList** — checkboxes on actionable rows, bulk bar, **bulk email modal** previewing the predefined template per company before send.
- **FilesModal** — split view: file list (left) + **PDF/image preview** (right); open/download/add/delete; note that reps normally upload statements & invoices.
- **ReconciliationView** (Bank transactions) — parsed transactions table; top panel "Documents the representative must provide"; per-row **Needs doc / No doc** two-way control (accountant override) with "Decided by" badge (Base rule / Learned / Accountant) and "remembered" indicator; disabled entry when no statement uploaded.
- **DeclarationDetail** — extracted amounts + treasury accounts, **Verificat toggle** gating the **Send payment email** button, email preview.
- **EmailPreview** — predefined templated body (RO), per kind (reminder/payment/payroll/report/doc-request).

## 5. Representative PWA

- Mobile-first home: company + month, **"Snap & upload a receipt"** (camera capture via `<input capture>` / `getUserMedia`), missing-documents checklist, latest reports.
- **Camera capture** → upload to API → optimistic "extracting…" toast.
- **Web Push** (VAPID) for reminders/new reports; in-app notifications via Supabase Realtime.
- Read-only views of own company's reports and payroll.
- Installable (manifest + service worker), offline shell + queued uploads when offline.

## 6. PWA setup

- `manifest.webmanifest` (name, icons, theme `#0D9488`, display standalone, start_url `/`).
- Service worker (Workbox): app-shell precache, runtime cache for static assets, **background sync** queue for offline uploads. Never cache sensitive API responses.
- Web Push subscription stored via API; VAPID public key from config.
- iOS note: web push works for added-to-home-screen PWAs (iOS 16.4+); camera via file input is the reliable cross-platform path.

## 7. Auth & guarding

- Supabase session → JWT in memory (+ refresh). `RequireRole` route guards. Representative routes additionally scoped to their `company_id` (server enforces; client hides).
- MFA: staff enroll TOTP on first login; Google Workspace 2FA satisfies it for Google sign-in.

## 8. API client & error handling

- Typed client from OpenAPI; TanStack Query hooks per resource.
- Global error boundary + toast for API errors; optimistic updates only for safe actions.
- All money formatted RO locale (`1.234,56 RON`); dates RO; UTC→local display.

## 9. Build order (frontend)
1. App shell, design tokens/components from prototype, auth + routing + guards.
2. Dashboard + Companies + Company detail (5 cards) against MOD-03/11 APIs.
3. Statements & invoices + ReconciliationView + FilesModal (MOD-04/05).
4. Taxes & payments + DeclarationDetail (MOD-07); Reports (MOD-06); Payroll (MOD-08).
5. Notifications + Tasks; bulk-email flows (MOD-09/10).
6. Representative PWA (camera, web push, offline); i18n RO/EN; a11y pass (WCAG 2.2 AA).
