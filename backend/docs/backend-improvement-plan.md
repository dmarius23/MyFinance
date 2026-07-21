# MyFinance — Backend Improvement Plan

> A prioritized, step-by-step roadmap for hardening and simplifying the **backend** (`backend/`).
> Derived from [`MyFinance-architecture-review.md`](../../docs/MyFinance-architecture-review.md) (snapshot 2026-07-05,
> commit `9ca791d`) and **re-verified against the code** while writing this plan. Companion doc:
> [`backend-clean-code-guidelines.md`](backend-clean-code-guidelines.md) — the conventions every step below
> should follow.

## How to use this document

Each step is **self-contained** so an independent agent can pick exactly one, do its own deep-dive →
plan → implement → test cycle, and ship it. Steps are ordered by importance (security → reliability →
production blockers → architecture/code-reduction → docs → optimizations). Within a priority band,
respect the `Depends-on` field.

Every step lists: **Goal · Why · Evidence · Approach · Acceptance · Size · Depends-on.**

- **Size:** `S` ≈ ≤0.5 day · `M` ≈ 1–2 days · `L` ≈ 3–5 days.
- **Net-LOC:** shown where the step moves the "end up with *less* code" goal.
- Line numbers are from commit `9ca791d`; treat them as anchors, not gospel — re-`grep` before editing.

**Guardrails for every step:** do not break multi-tenant isolation (RLS + fail-closed), keep the
`CrossTenantIsolationTest` green, add tests, and follow the clean-code guide. Do not regress the golden
rules in [`CLAUDE.md`](../../CLAUDE.md).

---

## Priority map

| Band | Theme | Steps | Status |
|---|---|---|---|
| **P0** | Security correctness (cheap, high-value) | S1, S2, S3 | ✅ **Done** — PR [#3](https://github.com/dmarius23/MyFinance/pull/3) (branch `chore/backend-improvements`) |
| **P1** | Reliability & correctness of core flows | S4, S5, S6 | ⬜ Not started |
| **P2** | Production blockers (stubbed features) | S7, S8 | ⬜ Not started |
| **P3** | Architecture & code reduction (*less code*) | S9, S10, S11, S12, S13 | ⬜ Not started |
| **P4** | Documentation & guardrails | S14 | ⬜ Not started |
| **P5** | Optimizations, hardening & hygiene | S15, S16, S17, S18, S19 | ⬜ Not started |

> **Per-step status:** S1 ✅ done · S2 ✅ done (scope reduced — multipart limits + allowlist/magic-byte guard
> already existed; only the 413 mapping was missing) · S3 ✅ done (issuer pinning, issuer derived from the
> JWKS URI). Deferred follow-ups discovered during P0: `DocumentServiceIT` repair → **S4**; failsafe/CI +
> `*IT`-suite isolation → **S15/S18** (see notes on those steps).

---

## P0 — Security correctness  ✅ Done (PR #3)

### S1. Fail-safe URL authorization backstop  ✅ Done
- **Goal:** gate URLs by role at the filter layer — `/api/v1/portal/**` → `REPRESENTATIVE`,
  `/api/v1/admin/**` → `SUPER_ADMIN`, other `/api/v1/**` → staff roles — *in addition to* the existing
  per-method `@PreAuthorize`.
- **Why:** `common/config/SecurityConfig.java:46` ends with `anyRequest().authenticated()`, so role
  enforcement today is **100% per-controller**. Verified all 22 real `@RestController`s carry
  `@PreAuthorize` (the only one without is `ApiExceptionHandler`, a `@ControllerAdvice`), so there is
  **no active hole**. But the default is *permit-any-authenticated*: a single future controller that
  forgets the annotation would silently expose staff data to a `REPRESENTATIVE` token. Tenant RLS still
  blocks cross-tenant reads, but **not** same-tenant staff surfaces or side effects. This is a
  fail-safe-defaults fix (golden rule #2).
- **Evidence:** `common/config/SecurityConfig.java:40-46`; role→authority mapping in
  `common/security/SupabaseJwtAuthoritiesConverter.java`.
- **Approach:** insert `requestMatchers(...)` rules before `anyRequest().authenticated()`:
  ```java
  .requestMatchers("/api/v1/portal/**").hasRole("REPRESENTATIVE")
  .requestMatchers("/api/v1/admin/**").hasRole("SUPER_ADMIN")
  .requestMatchers("/api/v1/**").hasAnyRole("TENANT_ADMIN", "EMPLOYEE", "SUPER_ADMIN")
  .anyRequest().authenticated()
  ```
  Confirm `SupabaseJwtAuthoritiesConverter` emits `ROLE_`-prefixed authorities (so `hasRole` matches).
  Add a `@WebMvcTest`/slice test: a rep token → **403** on a sample staff route, **200** on a `/portal`
  route; a staff token → the inverse.
- **Acceptance:** rep token blocked at the filter for staff routes; all existing tests green.
- **Size:** S. **Depends-on:** none (pairs with the negative tests in S18).

### S2. Multipart request limits + upload guardrails  ✅ Done (reduced scope — see status note)
- **Goal:** reject oversized / wrong-type uploads *before* the bytes are fully buffered.
- **Why:** there is **no** `spring.servlet.multipart.*` config, so Spring's ~128 MB default applies; the
  app's own 20 MB check runs only *after* the upload is buffered. No server-side content-type allowlist.
- **Evidence:** absence in `backend/src/main/resources/application.yml`; late check at
  `intake/application/DocumentService.java:240` (`MAX_SIZE_BYTES` at `:29`).
- **Approach:** add to `application.yml`:
  ```yaml
  spring:
    servlet:
      multipart:
        max-file-size: 20MB
        max-request-size: 25MB
  ```
  Add a magic-byte + content-type allowlist (PDF/PNG/JPEG) in `DocumentService`; keep the existing size
  check as defense-in-depth; leave a seam for the per-tenant quota (S16). Map the resulting
  `MaxUploadSizeExceededException` to a `413` in `ApiExceptionHandler`.
- **Acceptance:** >20 MB → `413` without OOM; disallowed type → `400`; existing upload tests green.
- **Size:** S. **Depends-on:** none.

### S3. JWT issuer/audience pinning + clock skew  ✅ Done (issuer pinning; audience intentionally not pinned)
- **Goal:** validate the token `iss` (and `aud` if Supabase sets it) and set an explicit clock skew —
  not signature + algorithm alone.
- **Why:** the decoder is Spring auto-config from `jwk-set-uri` + `jws-algorithms: [ES256, RS256]` only.
  No `issuer-uri`, no audience validator. A validly-signed token from a *different* Supabase project
  would pass on signature alone.
- **Evidence:** `application.yml` `spring.security.oauth2.resourceserver.jwt` block (jwk-set-uri +
  jws-algorithms, no issuer); `common/config/SecurityConfig.java:47-48`.
- **Approach:** define a `JwtDecoder` bean:
  ```java
  NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
  decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
      JwtValidators.createDefaultWithIssuer(expectedIssuer),   // + timestamp/skew
      audienceValidator));                                     // if aud is set
  ```
  Add `myfinance.supabase.issuer` config (the project's `.../auth/v1` URL); set ~30 s skew. Test that a
  wrong-issuer or expired token → `401` and a valid token still works. Note: the default `jwk-set-uri`
  is a `YOUR-PROJECT` placeholder, so a misconfig fails **closed** — preserve that.
- **Acceptance:** wrong-issuer/expired → `401`; valid still works.
- **Size:** S. **Depends-on:** none.

---

## P1 — Reliability & correctness of core flows

### S4. Move heavy work off the request thread + wire the outbox relay & worker  *(epic)*
- **Goal:** PDF extraction, reconciliation, and email delivery run **asynchronously** with at-least-once
  delivery, retry/backoff, DLQ, and idempotency — instead of inline in the HTTP request.
- **Why:** the `outbox_message` table has **zero Java** — no `OutboxMessage` entity, repository, writer,
  or relay (verified: the only Java references to "outbox" are comments/TODOs). Events are dispatched
  through Spring's synchronous `ApplicationEventPublisher`, so `StatementExtractionListener` (PDF
  parsing) and every email `send()` execute **on the request thread**. `RedisJobQueue` enqueues, but the
  consumer BRPOP loop is a TODO — no backoff, DLQ, or idempotency. This is the single largest reliability
  gap and it also unblocks S7 (SES retry).
- **Evidence:** `common/jobs/WorkerConsumers.java:23-24` (TODO), `common/jobs/RedisJobQueue.java:12`
  (TODO), `extraction/application/StatementExtractionListener.java` (sync listener), the four email
  `send()` methods (see S9). Partial index `idx_outbox_pending` already exists in the baseline migration.
- **Approach — ship as independent sub-steps:**
  - **S4a — outbox writer:** add an `OutboxMessage` JPA entity + repository in `common` (or a new
    `common/outbox`). Write outbox rows *inside* the business `@Transactional` for events whose dispatch
    must survive a crash (`DOCUMENT_UPLOADED`, "email requested"). Keep synchronous `ApplicationEvent`
    only for in-process, best-effort concerns.
  - **S4b — worker relay:** implement the relay in `WorkerConsumers` (worker profile): poll
    `outbox_message WHERE status='PENDING'` ordered by `created_at`, dispatch, mark `SENT`/`FAILED`,
    apply exponential backoff + an attempts cap → move to a DLQ status. Run the relay under a dedicated
    **SYSTEM DB role** (the migration TODO in review §8.4) so it can read cross-tenant routing rows.
  - **S4c — async extraction:** make extraction async via `@TransactionalEventListener(AFTER_COMMIT)` +
    an `@Async` executor **or** by enqueuing an `EXTRACT_DOCUMENT` job the worker consumes. Idempotency
    keyed by `document_id` (re-processing must be a no-op).
  - **S4d — queue hardening:** graceful shutdown, visibility timeout, single-flight per job key.
- **Acceptance:** upload returns before extraction finishes; killing the worker mid-batch loses no email
  (redelivered on restart); a poison message lands in the DLQ after N attempts; re-running any job is a
  no-op.
- **Also fix here (found during P0, 2026-07-07):** three `DocumentServiceIT` happy-path tests
  (`uploadsClassifiesListsAndDownloads`, `deleteRemovesDocument`, `tenantBCannotSeeOrDeleteTenantADocuments`)
  are currently **broken** and must be repaired as part of this step. Cause: (1) the `png()` fixture is 7
  bytes but the magic-byte guard added in `9872c91` needs ≥8, and one test sends PNG bytes labeled
  `image/jpeg`; (2) once the fixtures are fixed so the upload *succeeds*, it trips an
  `ObjectOptimisticLockingFailureException` on `Invoice` because a successful upload fans out to **3
  synchronous listeners** (extraction/reconciliation/reports) on the request thread — exactly what this
  step makes async. **S4 acceptance additions:** repair the fixtures to valid magic bytes + a consistent
  content-type, and prove the happy-path upload→delete flow is green once extraction runs
  async/idempotently (no optimistic-lock conflict). Do not attempt to fix these tests before S4 — the
  synchronous pipeline is the blocker.
- **Size:** L. **Depends-on:** informs S5 and S7.

### S5. Fix `RepresentativeService` invite/persist atomicity
- **Goal:** never leave an orphaned Supabase auth user when the local DB write fails.
- **Why:** `inviter.invite()` (Supabase GoTrue) is called *before* `users.save()`; if the JPA persist
  fails, the external auth user is orphaned. The code's own TODO admits there is no compensating delete.
- **Evidence:** `access/application/RepresentativeService.java:69-71` (+ TODO at `:70`).
- **Approach:** **preferred** — persist a `PENDING` `app_user` **and** an "invite" outbox row in one
  transaction; the worker performs the GoTrue invite after commit (reuses S4a/S4b). **Fallback** (if S4
  isn't done yet) — wrap the persist in try/catch and issue a compensating GoTrue delete on failure.
  Add a test that induces a save failure and asserts no orphaned user / compensation fired.
- **Acceptance:** induced persistence failure leaves no orphaned auth user.
- **Size:** M. **Depends-on:** S4a (if taking the outbox route).

### S6. Ingestion scheduler multi-instance safety
- **Goal:** guarantee exactly-once scheduling when the web tier is scaled horizontally.
- **Why:** `IngestionScheduler` runs in the **web** app (`@EnableScheduling` is on
  `MyFinanceApplication`). It is `@ConditionalOnProperty(myfinance.ingestion.poll.enabled=true)`, so it's
  **off by default** — this is *not* the active P0 the raw finding implied. But once poll is enabled and
  the web tier has >1 instance, every instance polls (no distributed lock) → duplicate Drive imports.
- **Evidence:** `MyFinanceApplication.java:14` (`@EnableScheduling`);
  `ingestion/application/IngestionScheduler.java:23` (conditional), `:37`/`:43` (`@Scheduled` crons).
- **Approach:** add **ShedLock** (Postgres lock table) around `pollFrequent`/`pollDaily`, **or** move the
  scheduler to the **worker** profile so exactly one process schedules. Document the chosen invariant in
  the clean-code guide.
- **Acceptance:** two instances + enabled poll → one run per tick (integration test or a clearly
  documented lock).
- **Size:** M. **Depends-on:** none (do *before* enabling poll in prod or scaling the web tier).

---

## P2 — Production blockers (stubbed features, behind existing ports)

### S7. Real `EmailSender` (Amazon SES) behind the existing port — ✅ DONE
- **Shipped:** `SesEmailSender` (AWS SDK v2 `sesv2`) selected by `@ConditionalOnProperty(myfinance.email.provider=ses)`;
  `LoggingEmailSender` stays the dev default. Raw-MIME send (jakarta.mail via `spring-boot-starter-mail`)
  so attachments (reports/payroll) work; region via `EMAIL_REGION` (EU for residency); recipients masked
  in logs (`EmailAddresses.mask`). The `EmailSender` port was relocated to `common/email` (the S13 move
  below) and `NotificationService` now resolves its From via `EmailEnvelopeService.system(...)`, so all
  five send-sites share one port + one envelope. Config: `myfinance.email.{provider,region,configuration-set}`.
  **Not yet done:** routing through the outbox relay for retry (still synchronous — see S4); LocalStack/SES-sandbox
  integration test (adapter compiles + MIME unit-tested; no AWS creds here for an end-to-end send).
- **Goal:** actually deliver email in production; keep `LoggingEmailSender` for dev.
- **Why:** only `LoggingEmailSender` exists; no SES/AWS SDK in the build (review §10). The `EmailSender`
  port already abstracts the seam.
- **Evidence:** `taxpayments/adapter/external/LoggingEmailSender.java`; port `EmailSender` (currently in
  `taxpayments/application` — relocate in S13).
- **Approach:** add AWS SDK v2 SES v2; implement `SesEmailSender` selected by
  `@ConditionalOnProperty(myfinance.email.provider=ses)`; document SPF/DKIM/DMARC; route delivery through
  the outbox relay (S4) so failures retry rather than blocking. Mask recipients in logs (S17).
- **Acceptance:** integration test against SES sandbox or LocalStack sends; dev profile still logs.
- **Size:** M. **Depends-on:** S4 (retry), S13 (port move).

### S8. Durable `DocumentStorage` (S3 / Supabase Storage) behind the existing port
- **Goal:** replace local-filesystem-only storage with durable, multi-instance-safe object storage.
- **Why:** `LocalFsDocumentStorage` is the only adapter (review §10) — not durable and not safe across
  multiple app instances. The `DocumentStorage` port already abstracts it.
- **Evidence:** `intake/adapter/external/LocalFsDocumentStorage.java` (path resolution at `:54-61`).
- **Approach:** implement `S3DocumentStorage` (or Supabase Storage) selected by config; keep the existing
  `storage_key` scheme; **stream** rather than buffer whole files; enable server-side encryption; serve
  downloads via signed URLs or proxy through the API **preserving the ownership re-check**. Keep the
  local adapter for dev. Fold in path-traversal hardening and secure temp handling (review §8.4;
  security items #4, #14).
- **Acceptance:** upload/download round-trip integration test on the real adapter; local dev unchanged.
- **Size:** M. **Depends-on:** none.

---

## P3 — Architecture & code reduction *(the "less code" wins)*

### S9. Collapse the 4× email-history stack into one generic mechanism  *(Net-LOC ≈ −800)*
- **Goal:** one shared email-history entity + repository + send/record service; per-type config only.
- **Why:** `TaxEmail`, `ReportEmail`, `PayrollEmail`, and `DocumentReminder` are near-identical entities
  (76–88 LOC each; same fields + `Status{SENT,FAILED}`), backed by 4 identical repositories, and driven
  by 4 copy-pasted `send()` methods that all do the same flow: resolve tenant/user → `withDayOfMonth(1)`
  → `EmailEnvelopeService.resolve` → try/`sender.send`/catch-FAILED → save a history row → return a view.
  ~1,200 LOC of near-duplication.
- **Evidence:** `taxpayments/application/TaxEmailService.java:57-77`,
  `reports/application/ReportEmailService.java:67-97`, `payroll/application/PayrollService.java:115-150`,
  `extraction/application/DocumentReminderService.java:81-105`; the 4 entities/repos in each module's
  `domain`/`adapter/persistence`; 3 near-duplicate email builders (duplicated Romanian month arrays).
- **Approach:** introduce `common/email/EmailHistory` (one entity) + `EmailDispatchService` that does
  envelope-resolve (reuse the existing `EmailEnvelopeService`) → send → record → return a view,
  parameterized by an `EmailKind` enum and an optional `relatedIds` column (covers `declaration_ids` /
  `document_ids`). For the table: either consolidate the 4 tables into one via Flyway, **or** start with
  a JPA mapped-superclass over the existing 4 tables to avoid a data migration, then consolidate later.
  Keep thin per-module facades only where a controller needs a module-specific view. **Migrate `reports`
  first** as the template, then the other three.
- **Acceptance:** all four modules route through the shared service; history endpoints unchanged; net LOC
  down ~800; per-module tests green (add the missing ones from S18).
- **Size:** L. **Depends-on:** S13 (do the `EmailSender` port move first).

### S10. Extract shared helpers: string normalization + web request boilerplate  *(Net-LOC negative)*
- **Goal:** remove triplicated `normalize()` and the repeated controller companyId+period parsing.
- **Why:** `normalize()` (diacritic-strip → alnum → upper) is copy-pasted three times, and the
  `companyId` + `period` request-param pattern is repeated ~119× across 22 controllers, with
  `.withDayOfMonth(1)` scattered through services.
- **Evidence:** `ingestion/application/FolderMapper.java:153-165`,
  `intake/adapter/external/HeuristicDocumentClassifier.java:126-133`,
  `intake/application/CompanyMatcher.java:57-62` (keeps-spaces variant).
- **Approach:** add `common/text/StringNormalizer` (with a `keepSpaces` flag) and unify the three call
  sites. Add a `@PeriodMonth` `HandlerMethodArgumentResolver` (or a small `PeriodMonth` value object)
  that parses and normalizes to the first-of-month once; optionally a shared base for companyId+period.
  Apply incrementally, controller by controller.
- **Acceptance:** a single normalizer with unit tests; controllers no longer repeat period
  normalization; behavior unchanged.
- **Size:** M. **Depends-on:** none.

### S11. Split `ReconciliationService` (902 LOC) into focused collaborators  *(LOC-neutral; maintainability)*
- **Goal:** separate the three responsibilities currently mixed in one class.
- **Why:** 902 LOC and 25+ public methods spanning (a) transaction↔invoice matching + allocations,
  (b) document-requirement classification (rule engine + learned `transaction_rule` overrides), and
  (c) period completeness + suggestions.
- **Evidence:** `extraction/application/ReconciliationService.java` (matching ~`:266-472`, link/unlink
  ~`:411-472`, completeness ~`:184`, suggestions ~`:623`).
- **Approach:** extract `TransactionMatcher` (3-tier matching + allocations) and `RequirementClassifier`
  (rule engine + learned overrides); keep `ReconciliationService` as the completeness/suggestions
  orchestrator that delegates to both. **Write characterization tests first** to lock current behavior,
  then extract. Preserve the public API used by controllers and `PortalService`.
- **Acceptance:** identical behavior (existing extraction tests + new characterization tests green); no
  resulting unit >~400 LOC.
- **Size:** M. **Depends-on:** none (do after S10 so shared helpers exist).

### S12. Reduce cross-module coupling to `company` persistence
- **Goal:** modules depend on `company`'s **application** layer, not its repository.
- **Why:** 11 non-`company` classes import `company.adapter.persistence.CompanyRepository` directly —
  reaching across a module boundary into another module's persistence adapter.
- **Evidence (import sites):** `tasks/…/TaskService`, `ingestion/…/IngestionService`,
  `portal/…/PortalService`, `taxpayments/…/TaxPaymentService`, `taxpayments/…/TaxDeclarationListener`,
  `intake/…/DocumentService`, `intake/…/DocumentFlagService`, `dashboard/…/DashboardService`,
  `extraction/…/InvoiceExtractionService`, `extraction/…/ReconciliationService`,
  `notifications/…/NotificationService`, `access/…/RepresentativeService`.
- **Approach:** expose the needed reads (`findById`, list-for-tenant, resolve-by-cui) on a
  `CompanyDirectory` port / `CompanyQueryService` in `company/application`; swap the import sites to
  depend on it. Mostly a mechanical import/DI swap; it also makes those services unit-testable without
  the `company` persistence layer.
- **Acceptance:** no non-`company` module imports `company...adapter.persistence`; an ArchUnit rule
  (S14) prevents regressions.
- **Size:** M. **Depends-on:** none.

### S13. Normalize misplaced classes & stale comments  *(small structural tidy)*  — 🟡 PARTIAL
- **Done:** `EmailSender` (+ `Message`/`Attachment`) moved to `common/email` (with `LoggingEmailSender`,
  the new `SesEmailSender`, and an `EmailAddresses` mask helper); all consumers + tests updated (S7).
  **Still open:** the shared email builders (`*EmailBuilder`) haven't moved yet (do with S9); the
  `AnafDeclarationExtractor` home decision and the `RlsConnectionProvider` / "single company" stale-comment
  sweep remain.
- **Goal:** put shared ports/utilities in `common`; delete stale comments.
- **Why:** the `EmailSender` port lives in `taxpayments/application` but is used by reports, payroll, and
  extraction (wrong home). `TenantContext` javadoc references a `RlsConnectionProvider` class that does
  not exist. `PortalService` comments still say "single company" though reps are multi-company since V26.
- **Evidence:** `taxpayments/application/EmailSender.java`; `common/security/TenantContext.java:10`;
  `portal/application/PortalService.java:26,30`.
- **Approach:** move `EmailSender` (+ its `Message`) and the shared email builders to `common/email`;
  decide and document the home of `AnafDeclarationExtractor` (currently `taxpayments/application` though
  it's an extractor — keep it if it's genuinely tax-domain, but say why). Grep-sweep and fix/delete
  stale comments (`RlsConnectionProvider`, "single company", any remaining `mod01_*` references).
- **Acceptance:** shared ports live in `common`; no stale `RlsConnectionProvider` / "single company"
  comments remain.
- **Size:** S. **Depends-on:** precede S9 and S7.

---

## P4 — Documentation & guardrails

### S14. Sync backend docs with the code + add ArchUnit guardrails
- **Goal:** make `backend/docs/MyFinance-backend-design-v1.md` and `README.md` describe what actually exists, and
  encode the key architectural invariants as tests.
- **Why (verified doc drift):** the design doc still says `mod01_*` package names (code uses plain
  `tenant`, `access`, …), `fiscal_declaration` (code: `tax_declaration`), trial_balance `lines/totals
  jsonb` (code: `report_json text`), AWS Bedrock (code: direct Anthropic API), SES + Thymeleaf (code:
  logging stub, no Thymeleaf), pgmq (never built), and MOD-13 chatbot (absent). The README overstates
  email-ingestion and chatbot.
- **Evidence:** `backend/docs/MyFinance-backend-design-v1.md` (project-structure & data-model & MOD sections);
  `README.md`.
- **Approach:** rewrite the drifted sections to match the code; add a **Status: implemented / stubbed /
  planned** column per module; link this plan and the clean-code guide; keep
  `MyFinance-architecture-review.md` as a dated snapshot with a pointer to this living plan. Add an
  **ArchUnit** test suite encoding: `domain` has no Spring/web imports; no controller → repository; no
  cross-module `...adapter.persistence` imports (backs S12).
- **Acceptance:** doc claims match a code spot-check; the ArchUnit suite runs green in CI.
- **Size:** M. **Depends-on:** reflects S9–S13 outcomes (do it last among the structural work).

---

## P5 — Optimizations, hardening & hygiene

### S15. CI security + supply chain: add SAST/SCA, upgrade dependencies
- **Why:** CI runs only `gitleaks` (review §9). PDFBox 3.0.3 parses untrusted PDFs (check
  CVE-2024-50379 OOM and any newer advisories).
- **Evidence:** `.github/workflows/ci.yml`; `backend/pom.xml` (PDFBox 3.0.3).
- **Approach:** add **SCA** (OWASP dependency-check or Dependabot) and **SAST** (SpotBugs or Sonar) as CI
  gates; upgrade PDFBox to the latest 3.x; confirm XXE is disabled in every XML parser (CAMT.053,
  e-Factura, ANAF); pin dependency versions.
- **Acceptance:** CI fails on a known-vulnerable dependency; XML parsers reject external entities.
- **Also fix here (found during P0, 2026-07-07) — integration tests never run in CI.** CI runs
  `mvn -B -e verify` (`.github/workflows/ci.yml`), but `backend/pom.xml` declares **no maven-failsafe
  plugin** and surefire excludes `*IT` by default, so **none of the ~11 `*IT` Testcontainers tests
  execute** — only `CrossTenantIsolationTest` runs (it is `*Test`-named). This is the blind spot that hid
  the broken `DocumentServiceIT` (see S4). **Add the failsafe plugin** (bind `integration-test`/`verify`),
  so the `*IT` suite runs in CI. Note: the `*IT` suite currently **fails wholesale when run together** —
  30s Hikari connection-acquisition timeouts (pool exhaustion across accumulated Spring contexts) + cross-IT
  data bleed (no per-test cleanup; e.g. `ReconciliationServiceIT` learned-rule rows). So enabling failsafe
  must be paired with the isolation fixes in S18 (or forked JVMs / pool+context tuning) or CI will go red.
- **Size:** M. **Depends-on:** pairs with S18 (IT isolation) before flipping failsafe on in CI.

### S16. Guardrails: pagination, targeted queries, per-tenant quota, rate limiting
- **Why:** ~16 list endpoints are unbounded; three services do `findAll()`-then-filter-in-Java; there is
  no rate limiting; `tenant.limits` (jsonb) is never enforced.
- **Evidence:** `ingestion/application/IngestionService.java:175` (per-file loop over all companies),
  `taxpayments/application/TaxPaymentService.java:86`, `dashboard/application/DashboardService.java:71`.
- **Approach:** add `Pageable` (with default + max page size) to list endpoints; replace
  findAll-then-filter with derived/`@Query` targeted queries and map lookups; add a Redis-backed
  (Bucket4j) rate limit on upload + email; enforce `tenant.limits` in `DocumentService`. Ship
  incrementally, endpoint by endpoint.
- **Acceptance:** list endpoints paginated; no `findAll()` in per-item loops; upload/email rate-limited.
- **Size:** M. **Depends-on:** none.

### S17. Operational hardening: PII log masking, non-root container, actuator, audit, temp files
- **Why:** `LoggingEmailSender` logs recipient + subject (PII); the backend `Dockerfile` sets no `USER`
  (runs as root); actuator `health`/`info` are `permitAll`; the audit `before/after` jsonb columns exist
  but are likely unpopulated; OCR temp files are deleted but not securely erased.
- **Evidence:** `taxpayments/adapter/external/LoggingEmailSender.java`; `backend/Dockerfile` (no `USER`);
  `common/config/SecurityConfig.java:42-45`; `common/audit/AuditEntry.java` &
  `common/audit/AuditRecorder.java`; `intake/adapter/external/OcrReclassifier.java:104-116`.
- **Approach:** add a log-masking helper (emails/names/amounts) and apply it at log sites; add a non-root
  `USER` to the Dockerfile; restrict actuator or bind it to an internal port; populate audit before/after
  (masking PII); secure temp handling (0600 perms + overwrite-then-delete, or `/dev/shm`).
- **Acceptance:** no PII/secret in logs; container runs non-root; audit rows carry before/after.
- **Size:** M. **Depends-on:** none (do the log-masking part alongside S7).

### S18. Test coverage for thin modules + negative-authZ tests
- **Why:** `payroll`, `notifications`, `portal`, and `dashboard` have a single, often-mocked test each;
  `extraction`, `taxpayments`, and `access` are well covered; `CrossTenantIsolationTest` is the real,
  mandatory isolation test.
- **Approach:** add Testcontainers integration tests for the four thin modules; add rep→company/rep→staff
  **negative** authorization tests (backs S1); write characterization tests before the S9 and S11
  refactors.
- **Acceptance:** every module has at least one Testcontainers integration test; a rep token is proven
  unable to reach another company or a staff endpoint.
- **Also fix here (found during P0, 2026-07-07) — the `*IT` suite is not runnable as a suite.** When all
  `~11` `*IT` classes run in one reactor they fail wholesale: (a) DB **connection-pool exhaustion** across
  accumulated Spring contexts (30s Hikari timeouts) and (b) **cross-IT data bleed** — no per-test cleanup,
  so tests see rows left by earlier ITs (e.g. `ReconciliationServiceIT.overrideCreatesLearnedRuleApplied…`
  and `manualLinkSupportsManyInvoicesPerTransaction`). Each IT passes in isolation. Fix as part of adding
  coverage: per-test DB cleanup/truncation (or `@Transactional`/rollback where RLS allows), a shared single
  context (consistent `@SpringBootTest` config to maximize context-cache reuse) and/or forked JVMs +
  pool/`spring-test` context-cache tuning. This is the prerequisite for turning on failsafe in CI (S15).
  Note: `UrlAuthorizationBackstopIT` was already made DB-free (filter-only 403 assertions) during S1 to
  avoid this class of flakiness — use it as the pattern for authZ slice tests.
- **The S1 negative-authZ tests already exist** (`UrlAuthorizationBackstopIT`, added in P0/S1) — extend,
  don't duplicate.
- **Size:** M. **Depends-on:** pairs with S1, S9, S11; unblocks S15 (failsafe in CI).

### S19. *(Later)* GDPR endpoints + defense-in-depth rep→company RLS
- **Why:** there are no data export/delete endpoints (review §11, MOD-12); company-level rep scoping is
  app-layer only — a rep→company RLS policy keyed on `representative_link` would be defense-in-depth; and
  `source_connection` / `import_file` declare only `USING` (should also spell out `WITH CHECK`).
- **Approach:** implement GDPR export (per-subject JSON) + delete/anonymize; add the rep→company RLS
  policy; normalize the two `USING`-only policies to include `WITH CHECK`.
- **Acceptance:** export/delete endpoints exist and are tenant-scoped; the new RLS policies pass a
  dedicated isolation test.
- **Size:** M. **Depends-on:** none.

---

## Traceability to the architecture review

Every punch-list item from [`MyFinance-architecture-review.md`](../../docs/MyFinance-architecture-review.md) §8.4/§10/§11
maps to a step here: SES email → **S7**; durable storage → **S8**; job backoff/DLQ + outbox relay →
**S4**; SAST/SCA → **S15**; rep→company RLS + `USING`-only normalization → **S19**; payroll-PII / log
masking → **S17**; large units (`ReconciliationService`) → **S11**; stale comments → **S13**; docs
oversell → **S14**.

## "Less code" scorecard

Net reduction is concentrated in **S9** (≈ −800 LOC), **S10**, and **S13**. **S11** and **S12** are
LOC-neutral maintainability moves. The security and reliability steps (S1–S8, S15–S19) necessarily add a
bounded amount of code — called out per step — but the P3 dedup work should leave the codebase **smaller
overall** than it is today.
