# MyFinance — Frontend Improvement Plan

> A prioritized, step-by-step roadmap for hardening and simplifying the **frontend** (`frontend/`).
> Derived from [`MyFinance-architecture-review.md`](../../docs/MyFinance-architecture-review.md) §5 (Frontend) / §6
> (PWA) (snapshot 2026-07-05) and **re-verified against the code** while writing this plan. Companion
> doc: [`frontend-clean-code-guidelines.md`](frontend-clean-code-guidelines.md). Backend counterparts:
> [`backend-improvement-plan.md`](../../backend/docs/backend-improvement-plan.md), [`backend-clean-code-guidelines.md`](../../backend/docs/backend-clean-code-guidelines.md).

## How to use this document

Each step is **self-contained** so an independent agent can pick one, do its own deep-dive → plan →
implement → test cycle, and ship it. Steps are ordered by importance (security → correctness/UX →
architecture/code-reduction → accessibility → stubbed features → docs/tests). Within a band, respect
`Depends-on`.

Every step lists: **Goal · Why · Evidence · Approach · Acceptance · Size · Depends-on.**

- **Size:** `S` ≈ ≤0.5 day · `M` ≈ 1–2 days · `L` ≈ 3–5 days.
- **Net-LOC:** shown where the step moves the "end up with *less* code" goal.
- Line numbers are from the 2026-07-05 snapshot — anchors, not gospel; re-`grep` before editing.

**The golden guardrail for every step:** the browser is untrusted. `RequireRole` and any client-side
role/claim check are **UX only** — the server (Postgres RLS + validated JWT) is the sole authority. No
step may turn the frontend into a security boundary.

**What the app already does well (keep it):** TypeScript `strict` with **zero `any`/`@ts-ignore`**; React
Query as the single server-state source; clean secret hygiene (only the public Supabase anon key ships);
**no XSS surface** (no `dangerouslySetInnerHTML`/`eval`; email preview is a `<textarea>`; file previews
use blob URLs); PWA correctly excludes `/api` from the service-worker cache; multi-stage Docker build,
non-root nginx runtime.

---

## Priority map

| Band | Theme | Steps |
|---|---|---|
| **P0** | Security correctness (cheap, high-value) | F1, F2, F3 |
| **P1** | Correctness & reliability of core UX | F4, F5, F6 |
| **P2** | Architecture & code reduction (*less code*) | F7, F8, F9, F10, F11, F12 |
| **P3** | Accessibility | F13 |
| **P4** | Missing/stubbed production features | F14, F15, F16 |
| **P5** | Test infrastructure, docs & hygiene | F17, F18 |

---

## P0 — Security correctness

### F1. Clear all client state on logout
- **Goal:** `signOut()` wipes the React Query cache and the active-company hint, not just the Supabase
  session.
- **Why:** `signOut` currently does *only* `await supabase.auth.signOut()`. The React Query cache
  (in-memory) and `activeCompanyId` (localStorage) survive logout, so on a firm's **shared device** the
  next person can briefly see cached financial data (until refetch) and inherits the stale company hint.
  This is **not** cross-tenant escalation — the backend re-validates every request and `activeCompany.ts`
  itself notes the header is "only a hint" — but it is real shared-device data remanence, and the fix is
  a few lines.
- **Evidence:** `src/auth/AuthProvider.tsx:45-46`; `src/lib/activeCompany.ts` (`setActiveCompanyId`);
  `src/lib/queryClient.ts`.
- **Approach:** in `signOut`, after `supabase.auth.signOut()` call `queryClient.clear()` and
  `setActiveCompanyId(null)`; consider a hard navigation to `/login` to drop in-memory component state.
  Add a test (F17) asserting the cache and `myfinance.activeCompanyId` are empty afterward.
- **Acceptance:** post-logout, the React Query cache is empty and `myfinance.activeCompanyId` is removed.
- **Size:** S. **Depends-on:** none (pairs with F17).

### F2. Add security headers in nginx
- **Goal:** set `Content-Security-Policy`, `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`,
  `Permissions-Policy` (and `HSTS` when TLS terminates here).
- **Why:** `nginx.conf` sets none of these. There is no XSS surface today, so a strict CSP is low-risk to
  adopt and is strong defense-in-depth against a future dependency compromise; `X-Frame-Options` /
  `frame-ancestors` closes clickjacking.
- **Evidence:** `frontend/nginx.conf` (only SPA fallback + `sw.js` no-cache).
- **Approach:** add `add_header` directives. Author a CSP allowing `self` + the Supabase origin in
  `connect-src` (auth + API) and `frame-ancestors 'none'`. The app currently relies on inline style
  objects, so either allow hashed/nonce styles now or (better) land F8 first to move styles into classes
  and then forbid `unsafe-inline`. Verify the service worker still registers and the PWA installs.
- **Acceptance:** responses carry the headers; app + PWA still work; no CSP violations in the console.
- **Size:** S. **Depends-on:** tighten CSP further after F8.

### F3. Build/deploy hardening — `.dockerignore`, reproducible install, explicit build
- **Goal:** stop build-context pollution and pin the build.
- **Why:** there is no `.dockerignore`, and the Dockerfile does `COPY . .` after `npm install`, so a
  developer's local `node_modules`, `.env*`, `.git`, and `dist` enter the build context (image bloat +
  risk of an `.env` leaking into a layer). `npm install` (not `npm ci`) is non-reproducible.
- **Evidence:** absent `frontend/.dockerignore`; `frontend/Dockerfile` (`COPY . .`,
  `npm install --no-audit`); `frontend/vite.config.ts` (no explicit `build` key).
- **Approach:** add `.dockerignore` (`node_modules`, `.env*`, `.git`, `dist`, `*.log`); switch to
  `npm ci`; set `build.sourcemap: false` explicitly (Vite's default is already off — make it intentional).
- **Acceptance:** the build context excludes those paths; the image builds via `npm ci`.
- **Size:** S. **Depends-on:** none.

---

## P1 — Correctness & reliability of core UX

### F4. Centralize server-state: query-key registry + shared query/mutation hooks  *(Net-LOC negative)*
- **Goal:** one source of truth for query keys and fetching; remove per-page hand-rolled `useQuery`.
- **Why:** query keys are ad-hoc strings scattered across pages and modals, and `RepHome` fires a
  **blanket `qc.invalidateQueries()`** (no key filter) on company switch — over-broad and fragile. Two
  email modals fetch with `useEffect` + `Promise.all`, **bypassing React Query**, in byte-identical code
  that differs only by a type name.
- **Evidence:** `src/pages/RepHome.tsx:55-87` (8 inline queries) and `:69` (blanket invalidate);
  `src/components/ReconModal.tsx:30-48`; `src/components/ReportEmailModal.tsx:17-36` vs
  `src/components/PayrollEmailModal.tsx:18-36`.
- **Approach:** add `src/lib/queryKeys.ts` (e.g. `keys.bank.txns(companyId, period)` → a stable tuple)
  and thin per-resource hooks (`useBankTxns`, `usePortalReport`, …) wrapping `useQuery`/`useMutation`
  with standardized `onError` and **targeted** invalidation. Migrate the two email modals onto a shared
  `useEmailDraft` hook backed by React Query. Replace blanket invalidations with keyed ones.
- **Acceptance:** no inline query-key string literals in pages; a company switch invalidates only the
  affected keys; the two email modals share one hook; behavior unchanged.
- **Size:** M. **Depends-on:** none.

### F5. Global error boundary + shared loading/empty/error UX
- **Goal:** no white-screen on a render/query error; consistent states across pages.
- **Why:** there is no error boundary or `Suspense` anywhere, so an unhandled error crashes the whole
  app (StrictMode). Each page inlines `if (isLoading) return <div>Loading…</div>` and handles — or
  silently swallows — errors differently.
- **Evidence:** (grep) no `ErrorBoundary`/`componentDidCatch`/`Suspense`; ad-hoc states in
  `src/pages/CompanyDetail.tsx:18`, `src/pages/Companies.tsx:26`, `src/pages/Statements.tsx`.
- **Approach:** wrap the router in a top-level `ErrorBoundary` (e.g. `react-error-boundary`) with a
  fallback + reset; add `<Loading>`/`<Empty>`/`<InlineError>` primitives (part of F8) and adopt them;
  surface query errors via a toast/inline pattern instead of swallowing.
- **Acceptance:** a thrown error shows the fallback, not a blank page; pages use the shared state
  components.
- **Size:** M. **Depends-on:** F8 (can land together).

### F6. apiClient & session hardening
- **Goal:** handle expired sessions and make requests robust and consistent.
- **Why:** `apiClient.ts` has no 401 handling — a 401 just throws `ApiError`, so the user sees errors
  rather than being routed to login — and no timeout/abort. URL query params are hand-interpolated in
  most modules, while `dashboard.ts` already uses `URLSearchParams` (inconsistent; the values are
  app-generated ISO/UUID today, so this is correctness/consistency, not an active bug).
- **Evidence:** `src/lib/apiClient.ts:28-59` (no 401 branch, no `AbortController`);
  `src/api/dashboard.ts` (URLSearchParams) vs `src/api/bank.ts` / `src/api/portal.ts` (template literals).
- **Approach:** on 401, trigger a Supabase session refresh or route to `/login`; add an
  `AbortController`/timeout; standardize query-string building (URLSearchParams or an `encodeURIComponent`
  helper) across `api/` modules.
- **Acceptance:** an expired token routes to login; requests time out cleanly; query strings are encoded
  uniformly.
- **Size:** M. **Depends-on:** none.

---

## P2 — Architecture & code reduction *(the "less code" wins)*

### F7. Extract a shared formatting module  *(Net-LOC negative)*
- **Goal:** one `src/lib/format.ts` for money / date / time / month-label.
- **Why:** `money`, `when`, `time`, and `monthLabel` (all `ro-RO` `toLocaleString`, with inconsistent
  0-vs-2-decimal precision) are redefined inline in ~12 components.
- **Evidence:** `RepHome.tsx:25`, `EmailPreviewModal.tsx:9`, `SendReminderModal.tsx:14`,
  `TaxPaymentModal.tsx:10-12`, `reportCharts.tsx:5`, `FirmLayout.tsx:21-23`, `NotificationLogModal.tsx:6-7`,
  `LinkInvoiceModal.tsx:11`.
- **Approach:** `src/lib/format.ts` exporting `money(n, { dp })`, `date`, `time`,
  `monthLabel(period, lang)`; replace the inline copies; agree one currency-precision policy.
- **Acceptance:** no inline formatter definitions remain; formatting is consistent app-wide.
- **Size:** S. **Depends-on:** none.

### F8. Build reusable UI primitives + migrate onto them  *(Net-LOC negative + a11y)*
- **Goal:** a small `src/components/ui/` set — `<Modal>` (overlay/box/header + focus trap + Escape +
  backdrop), `<Table>`, `<Button>`, `<Badge>`/`<Pill>`, `<Loading>`/`<Empty>` — reusing the existing
  `<Field>`.
- **Why:** there are no primitives; ~20 modal components each redefine the same `overlay`/`modalBox`/
  `darkHeader` `CSSProperties`; tables/lists and loading/empty states are re-implemented per page; inline
  style objects are pervasive.
- **Evidence:** `src/components/ReconModal.tsx:9-20` and `src/components/FilesModal.tsx:8-18` (identical
  modal styles); `src/pages/Statements.tsx` (div-based table); `src/components/Field.tsx` (the one
  existing primitive).
- **Approach:** create the primitives; the `<Modal>` bakes in accessibility (focus trap, `Escape`,
  `aria-modal`, focus restore). Migrate modals/pages incrementally. Move shared style objects into CSS
  classes/tokens so F2's CSP can forbid `unsafe-inline`.
- **Acceptance:** modals/tables/buttons use the primitives; duplicated style objects are gone; `<Modal>`
  traps focus and closes on Escape.
- **Size:** L. **Depends-on:** enables F5 and a tighter F2; pairs with F13.

### F9. Collapse the 15 `api/*.ts` modules via a resource factory  *(Net-LOC negative)*
- **Goal:** one `crudResource<T>(base)` helper; modules declare only what differs.
- **Why:** all 15 API modules repeat `list/get/create/update/status/remove` wrappers around
  `api<T>(path, init)` with inline `JSON.stringify`.
- **Evidence:** `src/api/companies.ts:33-45`, `src/api/representatives.ts:11-31`, `src/api/tasks.ts:42-54`.
- **Approach:** add a factory that builds the standard CRUD calls from a base path + types; keep bespoke
  endpoints hand-written; migrate module by module. Coordinate with F10 (codegen may supply the types).
- **Acceptance:** CRUD modules use the factory; ~500 LOC removed; the API surface is unchanged.
- **Size:** M. **Depends-on:** ideally after/with F10.

### F10. Wire OpenAPI type codegen to replace hand-written API types
- **Goal:** generate FE request/response types from the backend `/v3/api-docs` contract.
- **Why:** types are hand-maintained per module, and `apiClient.ts`'s own comment says a generated client
  "will eventually supersede" them — a real drift risk as the backend evolves.
- **Evidence:** `src/lib/apiClient.ts` header comment; no `openapi-typescript`/`orval` in `package.json`.
- **Approach:** add `openapi-typescript` (types only — keep the existing `api<T>` transport) as an npm
  script plus a CI check that regenerates and diffs the committed output; replace hand-written interfaces
  incrementally. Keep it type-only to avoid a heavy client rewrite.
- **Acceptance:** API types are generated; a CI step fails when they drift from the backend contract.
- **Size:** M. **Depends-on:** backend OpenAPI accuracy (backend plan **S14**).

### F11. Decompose the god components + extract hooks  *(LOC-neutral; maintainability)*
- **Goal:** split `RepHome`, `FilesModal`, `ReconModal`, and `Statements` into sub-components + custom
  hooks.
- **Why:** each mixes data-fetching, business logic, and 250+ lines of presentation; the `Statements`
  modals remount on every toggle (lost focus).
- **Evidence:** `src/pages/RepHome.tsx` (484), `src/components/FilesModal.tsx` (409),
  `src/components/ReconModal.tsx` (336), `src/pages/Statements.tsx` (219).
- **Approach:** extract view sub-components and data hooks (leaning on F4's hooks and F8's primitives).
  Write characterization / interaction tests first (F17) to lock behavior, then split.
- **Acceptance:** no component >~250 LOC; behavior unchanged; tests green.
- **Size:** M–L. **Depends-on:** F4, F8, F17.

### F12. Remove dead code  *(Net-LOC negative)*
- **Goal:** drop unused dependencies and placeholders.
- **Why:** `zustand` is a dependency but is never imported; `PagePlaceholder` fills `/admin/tenants`.
- **Evidence:** `frontend/package.json:20` (zustand — verified zero `src` imports); `src/App.tsx:75`.
- **Approach:** remove zustand; either implement or clearly mark the admin placeholder; sweep for any Vite
  starter-template leftovers. Add `knip` or `depcheck` to CI to keep it clean.
- **Acceptance:** no unused deps flagged; bundle a touch smaller.
- **Size:** S. **Depends-on:** none.

---

## P3 — Accessibility

### F13. Accessibility pass
- **Goal:** semantic HTML, keyboard access, focus management, labels, and adequate contrast.
- **Why:** the UI is div-soup — the `Statements` table is built from divs, `RepHome`'s header/nav are
  divs — modals have no focus trap or Escape-to-close, aria is sparse, heading hierarchy is faked with
  inline font-size, and contrast is likely below WCAG AA in places.
- **Evidence:** `src/pages/Statements.tsx` (div table), `src/pages/RepHome.tsx` (div header/nav), the
  modal components (no focus trap), `src/components/Field.tsx` (the only labeled-field pattern).
- **Approach:** adopt semantic elements (`<table>`, `<nav>`, `<main>`, real `<button>`/`<a>`); the F8
  `<Modal>` supplies focus-trap + Escape; associate labels with inputs; audit contrast against the design
  tokens; add `eslint-plugin-jsx-a11y` and fix what it flags.
- **Acceptance:** `jsx-a11y` lint passes; keyboard-only navigation works; modals trap focus; headings are
  semantic.
- **Size:** M. **Depends-on:** F8.

---

## P4 — Missing/stubbed production features (ranked in; some backend-dependent)

### F14. Web Push (VAPID) to replace 30-second polling
- **Goal:** real-time notifications for the rep PWA instead of a 30 s `refetchInterval`.
- **Why:** review §6 — push is designed but not built; `RepHome` polls every 30 s.
- **Evidence:** `src/pages/RepHome.tsx` (notifications query `refetchInterval: 30000`).
- **Approach:** add a push-subscription flow + a service-worker `push` handler; requires backend VAPID
  keys and a send path (coordinate with the backend web-push work). Keep polling as a fallback.
- **Acceptance:** a server-sent notification appears without a poll; graceful fallback when unsubscribed.
- **Size:** L. **Depends-on:** backend push support.

### F15. Offline upload queue for the rep PWA
- **Goal:** uploads made offline are queued and retried on reconnect.
- **Why:** review §6 — there is no offline queue; an offline submit just fails.
- **Evidence:** `src/pages/RepHome.tsx` (upload mutation, no retry/queue); Workbox config has no
  background-sync.
- **Approach:** use Workbox Background Sync (or an IndexedDB queue) to persist failed uploads and replay
  them on reconnect; surface queued/failed state in the UI.
- **Acceptance:** an upload started offline completes automatically once back online.
- **Size:** M–L. **Depends-on:** none (FE-only).

### F16. MFA (TOTP) enrollment UI
- **Goal:** let staff enroll and manage TOTP; the data model already supports it.
- **Why:** review §5 — `mfa_enabled` exists but there is no enrollment UI.
- **Approach:** build enrollment/verify screens against the Supabase MFA APIs; gate to staff roles.
- **Acceptance:** a staff user can enroll TOTP and is challenged for it on login.
- **Size:** M. **Depends-on:** Supabase MFA config.

---

## P5 — Test infrastructure, docs & hygiene

### F17. Stand up FE test infrastructure + high-value tests + SCA
- **Goal:** go from zero to a working test setup and a CI gate.
- **Why:** there are no tests and no tooling; CI runs lint + typecheck + build only; the Docker build uses
  `npm install --no-audit` (no dependency scanning).
- **Evidence:** no vitest/testing-library/playwright in `frontend/package.json`;
  `.github/workflows/ci.yml` (FE job); `frontend/Dockerfile` (`--no-audit`).
- **Approach:** add Vitest + React Testing Library. Write the first tests where they matter most — F1
  (logout clears state), `RequireRole`, `apiClient` (auth header + RFC-7807 parse + 401), and one
  critical modal flow — plus characterization tests ahead of F11. Add `npm audit`/Dependabot (SCA) and
  `knip`/`depcheck` to CI.
- **Acceptance:** `vitest` runs in CI; the listed tests pass; SCA gates the build.
- **Size:** M. **Depends-on:** enables F11 — do the infra first.

### F18. Sync FE docs to the code
- **Goal:** make `frontend/docs/MyFinance-frontend-design-v1.md` and the README describe reality.
- **Why (verified drift):** the design doc claims an OpenAPI-generated typed client (hand-written today,
  until F10), Zustand-or-context state (Zustand is unused), and "React Query hooks per resource" (no
  shared factory until F4).
- **Approach:** rewrite the drifted sections; add a **Status: implemented / stubbed / planned** column;
  link `frontend-clean-code-guidelines.md`; keep `MyFinance-architecture-review.md` as a dated snapshot.
- **Acceptance:** doc claims match a code spot-check.
- **Size:** M. **Depends-on:** reflects F4/F8/F10 outcomes.

---

## Traceability to the architecture review (§5 / §6 / §11)

No FE tests → **F17**; no error boundary → **F5**; accessibility → **F13**; polling-not-push → **F14**;
no offline upload queue → **F15**; MFA UI → **F16**; large `RepHome` → **F11**; Zustand unused / codegen
absent / stale design doc → **F12** / **F10** / **F18**.

## "Less code" scorecard

Net reduction is concentrated in **F7** (formatters), **F8** (modal/table/style dedup — the largest),
**F9** (api factory, ~−500 LOC), **F12** (dead code), and **F4** (shared hooks). **F11** is LOC-neutral
maintainability. The security steps (F1–F3, F6), the stubbed features (F14–F16), and the test infra
(F17) add a bounded amount of code — flagged per step. Given how much duplication exists today, the app
should end up **smaller overall**.
