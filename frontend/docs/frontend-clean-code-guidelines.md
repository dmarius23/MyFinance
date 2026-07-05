# MyFinance — Frontend Clean-Code Guidelines

> Conventions every frontend change (human **or** AI) must follow. Distilled from what the app already
> does well (strict TypeScript, React Query discipline, clean secret hygiene, no XSS surface) plus the
> fixes in [`frontend-improvement-plan.md`](frontend-improvement-plan.md). Backend counterpart:
> [`backend-clean-code-guidelines.md`](../../backend/docs/backend-clean-code-guidelines.md). When a rule here and
> [`CLAUDE.md`](../../CLAUDE.md) disagree, CLAUDE.md wins.

These are **rules for new and changed code**, not a demand to rewrite the tree at once. Follow them when
you touch a file; use the improvement plan for staged refactors. Steps referenced as `F1`…`F18` point at
that plan.

---

## 1. Security-UX invariants (never violate)

The frontend is **not** a security boundary. It holds a signed token and calls an API; all authority is
re-derived and enforced server-side (Postgres RLS + validated JWT).

- **Client role checks are UX only.** `auth/RequireRole.tsx` and any check on a role/claim only decide
  what to *show*. Never gate anything security-relevant on them — the server already does.
- **Nothing secret ships in the bundle** beyond the public Supabase anon key. Never reference a
  service-role key or any private key in frontend code.
- **Clear client state on logout.** `signOut()` must `queryClient.clear()` and `setActiveCompanyId(null)`
  (F1) — cached financial data and the company hint must not survive to the next user on a shared device.
- **No HTML injection.** No `dangerouslySetInnerHTML`, `eval`, or `innerHTML`. Render server/email text as
  text; render document previews via blob URLs. (Both are already done — hold the line.)
- **`X-Company-Id` is a hint, not authorization.** Send it, but never assume it grants access — the
  backend validates it against the rep's assignments on every call.

## 2. State management

- **React Query is the single source of server state.** No `fetch`/`axios` in `useEffect` for server data
  (the two email modals that do this are being migrated in F4 — don't add more).
- **Every query key comes from the `lib/queryKeys.ts` factory** (F4). No inline key string literals in
  components — they cause silent cache-invalidation drift.
- **Mutations invalidate specific keys**, never a blanket `queryClient.invalidateQueries()` with no
  filter.
- **Client state stays minimal** — `activeCompany` (localStorage hint) and `period` (Context). Don't add
  a second state library; **Zustand is unused and slated for removal** (F12).

## 3. Component & layer boundaries

- **Pages orchestrate, hooks fetch, primitives present.** A page composes hooks and UI; it should not
  contain fetch wiring or 250 lines of markup.
- **Keep components under ~250 LOC.** Extract a custom hook when a component owns more than ~2 queries or
  any non-trivial logic (see the F11 decompositions of `RepHome`/`FilesModal`/`ReconModal`/`Statements`).
- **Reuse `components/ui/` primitives** — `<Modal>`, `<Table>`, `<Button>`, `<Field>`, `<Loading>`,
  `<Empty>`, `<Badge>` (built in F8) — instead of re-inlining markup and style objects.

## 4. API layer

- **All network calls go through `lib/apiClient.ts`** (`api` / `upload` / `download`). No component calls
  `fetch` directly.
- **Types come from OpenAPI codegen** (F10), not hand-written per module — this keeps the FE in lockstep
  with the backend contract.
- **CRUD modules use the resource factory** (F9); only bespoke endpoints are hand-written.
- **Build query strings with `URLSearchParams` / `encodeURIComponent`**, never raw template
  interpolation.
- **Handle 401 centrally** (refresh or route to login) — don't let each caller reinvent it (F6).

## 5. Styling

- **Prefer CSS classes / design tokens over inline `React.CSSProperties` objects.** Inline styles force
  CSP to allow `unsafe-inline` (F2) and get copy-pasted between components. Share the tokens; don't
  duplicate style objects.

## 6. Formatting & i18n

- **Money/date/period formatting only via `lib/format.ts`** (F7) — never redefine a `toLocaleString`
  formatter inline.
- **All user-facing text goes through `t()`.** No hardcoded RO/EN in JSX (audit targets: `CompanyDetail`,
  `Companies`, `RepHome` `title="download"`).
- **Keep the RO and EN catalogs in sync** — add both when you add a key.

## 7. Errors & loading

- A **top-level error boundary stays mounted** (F5); a thrown error shows a fallback, never a blank page.
- Use the shared `<Loading>`/`<Empty>`/`<InlineError>` components; don't inline one-off spinners.
- **Surface query errors** to the user (toast/inline). Never silently swallow a failed request.

## 8. Forms

- Controlled inputs with the shared `<Field>` wrapper.
- **Validate on the client for UX** (mirroring the backend's Bean Validation), but treat the server as
  authoritative; show field-level errors, not just a disabled button.

## 9. Accessibility (part of Definition of Done)

- **Semantic HTML** — `<table>` for tables, `<nav>`/`<main>`/`<header>` for layout, real `<button>`/`<a>`
  for interaction (no click-handlers on bare `<div>`s).
- **Modals trap focus, close on Escape, restore focus** on close (the F8 `<Modal>` provides this).
- **Inputs have associated labels**; icons that convey meaning have `aria-label`.
- Meet **WCAG AA contrast**. `eslint-plugin-jsx-a11y` must pass.

## 10. PWA

- **Never cache `/api` in the service worker** (already denylisted — keep it).
- **Clear caches appropriately on logout** (F1).
- **Offline uploads queue and retry** (F15); **prefer push over polling** once available (F14).
- Keep the app-shell update flow working (`sw.js` is served `no-cache`).

## 11. Build & TypeScript

- **Keep TS `strict` with zero `any`/`@ts-ignore`.** This is the current state — hold the line; it's one
  of the codebase's real strengths.
- **Reproducible installs** (`npm ci`), a maintained `.dockerignore`, and **no production sourcemaps**
  (F3).
- **Code-split heavy routes** with `React.lazy` — don't regress to one giant bundle.

## 12. Testing bar (Definition of Done)

- **Vitest + React Testing Library** (stood up in F17). Every new hook/component ships with a test.
- **Security-sensitive flows are covered**: logout-clears-state (F1), `RequireRole`, and `apiClient`
  (auth header + RFC-7807 parse + 401 handling).
- **Characterization tests precede refactors** (before the F11 decompositions).
- **CI runs** lint + typecheck + tests + SCA + `knip`/`depcheck`; no secrets in the bundle.

---

*Keep this doc next to the code. When a convention changes, update this file in the same PR — and if the
change is structural, update [`frontend-improvement-plan.md`](frontend-improvement-plan.md) and the FE
design docs (F18) too.*
