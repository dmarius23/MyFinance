# Getting started in Claude Code

This repo is ready to build. Planning is done (see `docs/`), and `CLAUDE.md` is the build brief Claude Code reads automatically. Follow the steps below to move from specs to code.

## 1. Prerequisites (one-time, on your machine)

Check each, install what's missing:

| Tool | Why | Check | Install |
|---|---|---|---|
| Node.js 18+ | Claude Code CLI + React frontend | `node --version` | nvm: `nvm install --lts` |
| JDK 21 | Spring Boot backend (you have an old JDK 11 — upgrade) | `java -version` | `brew install openjdk@21` |
| Maven 3.9+ | backend build (or use the wrapper Claude Code generates) | `mvn -version` | `brew install maven` |
| Git | version control | `git --version` | preinstalled on macOS |
| Supabase project | DB + Auth + Storage (EU/Frankfurt region) | — | create at supabase.com |
| Claude Max subscription | includes Claude Code | — | you have this |
| Docker (optional) | local Postgres/Redis if you don't want cloud while developing | `docker --version` | Docker Desktop |

## 2. Install Claude Code

```bash
npm install -g @anthropic-ai/claude-code@latest   # do NOT use sudo
claude --version
```

First launch opens the browser to sign in to your Anthropic account (uses your Max plan).

## 3. Initialize the repo

```bash
cd "~/Documents/personal/projects/MyFinance"
git init
git add . && git commit -m "Planning docs, specs, prototype (pre-build)"
claude
```

Claude Code auto-reads `CLAUDE.md` on launch — it points to the backend/frontend design docs, the prototype, and the PoC findings.

## 4. Build order — prompts to use

Work one step at a time; review and commit between steps.

**Step 1 — Scaffold:**
> Read CLAUDE.md and backend/docs/MyFinance-backend-design-v1.md and frontend/docs/MyFinance-frontend-design-v1.md. Execute build-order step 1: scaffold the Spring Boot modular monolith + worker process and the React+Vite PWA, wire Supabase (DB/Auth/Storage), set up Flyway with the RLS baseline, and add CI (GitHub Actions: build, test, SAST/SCA/secret-scan). Commit at each working milestone. Skeleton only — no module business logic yet.

**Step 2 — Foundation modules:**
> Implement MOD-01 (Tenant Administration), MOD-02 (Users/Access with Supabase JWT validation + tenant RLS context), and MOD-03 (Company Management: tax profile, treasury accounts, HR registry). Add a cross-tenant isolation test for every data-access path.

**Step 3 — PoC core (the de-risked heart):**
> Implement MOD-05 extraction (BankStatementParser with a BRD implementation, InvoiceParser, DeclarationExtractor reading embedded d212.xml, TrialBalanceParser with totals cross-check, ReceiptExtractor via Bedrock/Claude vision) and MOD-04 (reconciliation: IBAN+amount+period matching; deterministic+learned transaction document-requirement classification with accountant override) and MOD-07 state-payment email. Build the extraction fixture suite from the sample documents I add to backend/src/test/resources/fixtures/ and assert extracted values, cross-checks, and missing-doc detection.

**Step 4 — MVP modules:** MOD-06 reports, MOD-08 payroll, MOD-09 notifications (email + in-app), MOD-10 tasks, MOD-11 dashboards, MOD-12 audit/GDPR.

**Step 5 — MVP+:** MOD-13 chatbot + KB, the representative PWA polish (camera, web push, offline), i18n RO/EN.

**Step 6 — Harden:** end-to-end tests, security/RLS sweep, observability, deploy (Hetzner backend+worker, Supabase, frontend on Vercel/Cloudflare).

## 5. Add the real sample documents for fixtures

Drop the four validated PoC files into `backend/src/test/resources/fixtures/` so the extraction tests run against real data:
- BRD bank statement (text PDF)
- BT Leasing invoice (text PDF)
- Situație profit / trial balance (text PDF)
- ANAF D212 (with embedded `d212.xml`)

## 6. Tips

- Let Claude Code run the build/test loop itself; review diffs and commit at green milestones.
- Keep secrets in `.env` (git-ignored); use `.env.example` for shape.
- Re-run the cross-tenant isolation tests after any data-access change — it's the #1 golden rule.
- Come back to Cowork for spec/prototype changes; do code in Claude Code.
