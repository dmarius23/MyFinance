# frontend/CLAUDE.md — MyFinance frontend

Loaded **in addition to** the root [`/CLAUDE.md`](../CLAUDE.md) (global rules) whenever you work under
`frontend/`. This is the **frontend layer**: stack, conventions, security-UX invariants, and doc pointers.
It does not repeat the root — read that too.

## Source-of-truth docs (`frontend/docs/` + the global prototype)

- [`MyFinance-frontend-design-v1.md`](docs/MyFinance-frontend-design-v1.md) — frontend/PWA build spec:
  stack, routes/pages mapped to the prototype, components, auth, i18n, PWA (camera, web push).
- [`frontend-clean-code-guidelines.md`](docs/frontend-clean-code-guidelines.md) — **conventions to follow
  for all new or changed frontend code.**
- [`frontend-improvement-plan.md`](docs/frontend-improvement-plan.md) — prioritized hardening/refactor
  roadmap (steps F1…F18); pick one at a time.
- [`../docs/MyFinance-prototype.html`](../docs/MyFinance-prototype.html) (global) — the clickable UX
  reference every screen maps to.

## Stack & conventions

- **React 18, Vite 5, TypeScript `strict`** (hold the line: **zero `any`/`@ts-ignore`**), React Router 6.
- **TanStack React Query** is the single server-state source; **Supabase JS for auth only**; react-i18next
  (RO default / EN fallback); vite-plugin-pwa. (Zustand is a dependency but **unused** — slated for removal.)
- **Two shells:** `FirmLayout` (staff console) and `RepHome` (rep mobile PWA). Every API call goes through
  `lib/apiClient.ts` (Bearer JWT + `X-Company-Id` header, RFC-7807 error parsing). Response types are
  hand-written today; OpenAPI codegen is the intended source.
- Prefer shared query-key factory + hooks over inline `useQuery`; reusable UI primitives over copy-pasted
  markup/inline styles (see the improvement plan).

## Frontend security-UX invariants (extend the global rules)

- **The browser is untrusted.** `auth/RequireRole.tsx` and any client role/claim check are **UX only** —
  the server (Postgres RLS + validated JWT) is authoritative. Never gate anything security-relevant on the
  client.
- Only the **public Supabase anon key** ships in the bundle — never a service-role or private key.
- **Clear the React Query cache and `activeCompanyId` on logout** (shared-device data safety).
- **No `dangerouslySetInnerHTML`/`eval`.** Render server/email text as text; render document previews via
  blob URLs. Never cache `/api` responses in the service worker (already denylisted — keep it).

## Testing & definition of done

Vitest + React Testing Library (to be stood up); cover the security-sensitive flows (logout-clears-state,
`RequireRole`, `apiClient` auth/401). `eslint` (with `jsx-a11y`) + typecheck + build green; SCA in CI; no
secrets in the bundle. **Accessibility is part of DoD:** semantic HTML, focus-trapped modals with
Escape-to-close, labelled inputs, WCAG-AA contrast.
