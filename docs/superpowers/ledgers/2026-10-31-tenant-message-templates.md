# SDD ledger — plan: /Users/marius/mf-sdd/docs/superpowers/plans/2026-10-31-tenant-message-templates.md

Spec: docs/superpowers/specs/2026-10-31-tenant-message-templates-design.md (reachable, read)
Worktree: /Users/marius/mf-sdd on branch feat/tenant-message-templates
Base at start: 3413d01 (chore: ignore .superpowers/) — ancestors 0f7d2b9 plan, 6ffb491 spec
Baseline: `mvn -q test` exit 0 before any task. Docker 29.5.3 available (ITs can run).
Node 24.14.1, no nvm. frontend/.env absent in worktree (copy blocked; secrets) — affects Tasks 11-12 only.

## Pre-flight conflict scan

### Cross-task: every pair sharing a file or interface

| Tasks | Produces → Consumes | Finding |
|---|---|---|
| 1 → 2 | `MessageTemplateKind` (6 constants) → rewritten with `placeholders()/blocks()/allNames()/SIGN_OFF/isReminder()` | OK — intentional two-step, flagged in T1 Interfaces |
| 1 → 5 | `MessageTemplate` entity + `MessageTemplateRepository` (incl. `deleteByTenantIdAndKind`) → service CRUD | OK — repo declares all three methods T5 calls |
| 2 → 5 | `TemplateRenderer.render`, static `normalize` → `render()`, `save()` | OK |
| 2 → 10 | static `normalize` → validator | OK — static, same signature |
| 3 → 4 | `EXPECTED_PAYROLL/REPORT/TAX_BODY` package-private statics → golden assertions | OK **only if** both classes stay in `ro.myfinance.messaging` — see Finding D |
| 3 → 7 | `reportData()` → fixture reuse | OK — T7 Step 1 explicitly widens it from `private static` to `static` |
| 3 → 6,7,8 | `CurrentMessageWordingTest` → each repoints its own tests | OK sequential; T6 must add the `TemplateRenderer`/`DefaultMessageTemplates`/`MessageTemplateKind` imports T3 does not have |
| 4 → 6,7,8,9 | `SignOff.names`, `DefaultMessageTemplates.of` | OK — `block()` retained for the invite |
| 5 → 6,7,8,9,10,13 | `RenderedMessage`, `MessageTemplateService` | OK — single producer, consistent signatures |
| 6 → (self) | `Computation` gains `subject` as last component | OK — no earlier task reads its arity; T6 Step 7 says fix flagged call sites |
| 7,8 → controllers | `composeBody(UUID, LocalDate) -> String` preserved | OK — `ReportController`/`PayrollController` untouched by design |
| 9 → 12 | `GET .../document-reminders/body` → `remindersApi.body` | OK — shape `{state, subject, body}` matches both sides |
| 10 → 11 | 4 template endpoints → `templatesApi` | OK — paths and payloads match |
| 11 → 12 | both edit `frontend/src/i18n.ts` (11 adds `templates.*`, 12 deletes `email.*`) | OK — disjoint key sets, sequential; both must mirror RO and EN |

### Per-task internal consistency

| Task | Self-agreement (tests vs code vs files) | Finding |
|---|---|---|
| 1 | isolation test seeds `kind='TAX'` as text; entity `@Enumerated(STRING)` | consistent |
| 2 | 14 renderer tests vs the 3 documented rules | consistent; single-pass test pins Review Focus 1 |
| 3 | pins existing API, no production change | consistent — but see Findings A/B/D |
| 4 | golden targets = T3 constants | consistent; whitespace iteration anticipated in Step 8 |
| 5 | mock-repo unit tests vs tenant-scoped reads | consistent; `Identity` 4-arity verified |
| 6 | deletes `build()` | **Finding A** |
| 7 | deletes `body()/subject()` | no existing test outside `CurrentMessageWordingTest` uses them (verified) |
| 8 | deletes `body()/subject()` | **Finding B** |
| 9 | changes reminder subject to `Aprilie 2026` | safe — no backend test asserts the old `MM.yyyy` form (verified) |
| 10 | validator throws `IllegalArgumentException` → 400 | consistent with `ApiExceptionHandler:32` (verified) |
| 11 | new file + mount + i18n | consistent |
| 12 | `git add -A frontend/src` | safe — `frontend/.env` is gitignored at `.gitignore:29` (verified) |
| 13 | service-level isolation over T5 | consistent |

### Rulings made before execution

**Ruling A: `PaymentCalculatorTest` must be repointed, not left broken.** — Task 6 deletes
`PaymentEmailBuilder.build(...)`, and `backend/src/test/java/ro/myfinance/taxpayments/PaymentCalculatorTest.java`
calls it at lines 69, 86, 90; the plan never mentions this file. Repoint those three call sites to
`new PaymentEmailBuilder().values(...)` rendered through the `TAX` default with `TemplateRenderer`,
keeping every existing assertion unchanged — the test's subject is the calculator's amounts, not the
prose, so the assertions remain valid. — If wrong: a calculator test asserts amounts through one extra
indirection; no production behaviour rides on it.

**Ruling B: `PayrollServiceTest`'s three wording tests are repointed, not deleted.** — Task 8 deletes
`PayrollEmailBuilder.body/subject`, which that file uses at lines 60, 65, 71, 78; the plan never mentions
it. Two of those tests encode a business rule worth keeping (the firm's name is always last; never an
unfilled `[Numele contabilului]`), so repoint all three through `values(...)` + the `PAYROLL` default
rather than dropping them as redundant with Tasks 3-4. — If wrong: three payroll assertions overlap the
golden tests; harmless duplication, and the firm-signs-last rule stays covered.

**Ruling C: delete Task 3 Step 4's package-move fallback.** — It says to move
`CurrentMessageWordingTest` into `ro.myfinance.reports` if `ReportEmailBuilder` is package-private. All
three builders are in fact `public` (verified), so the branch is dead — and taking it would move the
`EXPECTED_*` constants out of `ro.myfinance.messaging` and break Task 4's golden tests, which read them
across classes in that package. The implementer must keep the test in `ro.myfinance.messaging`. — If
wrong: nothing; the condition cannot arise.

---

## Progress

Task 1: review 1 — spec ✅, quality Approved; 1 Important (test-helper SQL concatenation), 3 Minor.
Task 1: ⚠️ resolved by controller — migration sequence V60 → V61 → V62, no gaps, no duplicate versions.
Task 1: fix round 1/5 (1 addressed, 0 open — seedTenant now PreparedStatement with ?::uuid; commits e5032d5..7efdcd3)
Task 1: minor (deferred): MessageTemplate has no @DynamicUpdate, so saves issue full-column UPDATEs — negligible on a six-row-per-tenant table.
Task 1: minor (deferred): TENANT_A/TENANT_B are String not UUID in the isolation test — matches the existing PeriodReportIsolationTest pattern.
Task 1: minor (deferred): repository exposes deleteByTenantIdAndKind, ahead of Task 1's stated interface — the plan's own code block included it and Task 5 requires it.
Task 1: complete (commits 3413d01..7efdcd3, review clean)
Task 2: review 1 — spec ✅ (all six placeholder contracts verified name by name), quality Approved; 1 Important (asymmetric regex escaping), 2 Minor.
Task 2: ⚠️ resolved by controller — the enum's only consumer is MessageTemplate with @Enumerated(STRING), which is name-based and unaffected by the enum gaining a constructor.
Task 2: Ruling: also closed the untested elision gate (two placeholders, one valued one empty) in the same fix round — Minor by the rubric, but it is the one untested branch of the predicate every message now renders through, and marginal cost inside an already-running round was one test method. — If wrong: one extra test in the core engine's suite.
Task 2: fix round 1/5 (2 addressed, 0 open — regex now `\\}\\}`, verified behaviour-preserving; elision-gate test added; commits fd37d02..c9d3a30)
Task 2: minor (deferred): REMINDER_NOTHING_RECEIVED and REMINDER_DOCS_ONLY placeholder sets are not asserted individually (they share the same set, so a typo in one would not be caught).
Task 2: complete (commits 7efdcd3..c9d3a30, review clean)
Task 3: review 1 — spec ✅, quality Approved, no Critical/Important. No fix round needed.
Task 3: plan defect found by implementer — `TaxCategory.IMPOZIT_PROFIT` does not exist; the enum is {IMPOZIT, CAS, CASS, CAM, TVA, TVA_EXTERN}. Used `IMPOZIT`, which `shortLabel` maps to "Impozite" via its default fallback (reviewer verified against both the enum and the dispatch). Rendered output unchanged.
Task 3: all three EXPECTED_* strings matched real builder output byte-for-byte on the first run — the plan's reconstructed wording was accurate, so the anticipated whitespace iteration did not materialise here. Task 4 still has to reproduce those bytes through a template.
Task 3: minor (deferred): the Kpis fixture passes six positional nulls with no labels — self-healing (a field change breaks compilation rather than silently passing).
Task 3: complete (commits c9d3a30..81852b4, review clean)
Task 4: review 1 — spec ✅ (all six defaults, placeholder sets walked name by name, trailing-newline behaviour per kind), quality Approved, no Critical/Important. No fix round needed.
Task 4: controller verified independently with git that CurrentMessageWordingTest has zero commits and zero diff in 81852b4..9443971, and SignOff.java is +15/-0 so block() is intact — the regression net was not bent to make the templates pass.
Task 4: ⚠️ resolved — TemplateRenderer calls values.get() only for tokens found in the template (TemplateRenderer.java:71), so extra map keys are ignored by construction; a template omitting a placeholder its kind declares is therefore fine.
Task 4: ⚠️ resolved — allNames() adds SIGN_OFF explicitly (MessageTemplateKind.java:58), pinned by everyKindAllowsSignOff over all six kinds.
Task 4: load-bearing whitespace for Tasks 6-8: TAX body ends with \n (REPORT/PAYROLL do not); {{paymentSummary}} carries no trailing newline and sits directly above "Conturi:"; the blank line before TAX's closing is literal template text, not part of the accounts block; SignOff.names returns "" (not "\n") when both parts are blank so elision drops the line.
Task 4: minor (deferred): Template record has a spurious empty body; Pattern imported inline in one test rather than at top level.
Task 4: complete (commits 81852b4..9443971, review clean)
Task 5: review 1 — spec ✅ (fail-closed traced through all five public methods, transaction annotations, body normalized but not trimmed, single-query effectiveAll), quality Approved; 2 Important (both test gaps), 2 Minor.
Task 5: Ruling: folded the Minor unused-import removal into the fix round — it sits in the same file as the two Important test additions, so marginal cost was nil. — If wrong: one lint-level cleanup landed a task earlier than the final review would have triaged it.
Task 5: fix round 1/5 (3 addressed, 0 open — blank-subject fallback test, effectiveAll merge test asserting the customized entry's subject AND body plus real default text on another kind, unused import removed; commits 620cb3f..a5057aa). Re-reviewer independently reasoned both new tests would fail against the bugs they target.
Task 5: re-reviewer's out-of-scope observation (report mentions a buildEffective helper absent from the diff) is a scope artifact — it reviewed only the fix diff. Controller verified the helper is real at MessageTemplateService.java:89, shared by effective() (:47) and effectiveAll() (:64), introduced in the original Task 5 commit.
Task 5: minor (deferred): save() reads TenantContext twice (once via tenant(), once for updated_by) — impossible to diverge today, but resolving the identity once would be less fragile.
Task 5: note — the suite reports 22 skips throughout. Controller confirmed these are pre-existing fixture- and Docker-gated tests (the real bank/receipt PDF fixtures are gitignored PII, absent from any clone); MessageTemplateIsolationTest itself runs 4/4.
Task 5: complete (commits 9443971..a5057aa, review clean)
Task 6: returned DONE_WITH_CONCERNS. The concern was real and introduced by this task: compute() now calls MessageTemplateService.render(), which throws when no tenant is bound, and TaxPaymentServiceTest never bound one. Controller established the three affected tests are gated on ANAF PDF fixtures (gitignored PII, absent from both the worktree and the user's main checkout), so they skip everywhere today — but backend/CLAUDE.md instructs developers to add those PDFs, which would have tripped the trap.
Task 6: Ruling: fixed that before review rather than after, per the skill's handling of correctness concerns — bound TenantContext in @BeforeEach and cleared it in @AfterEach (a leaked ThreadLocal would contaminate other tests in the same fork). Implementer empirically confirmed the path with a throwaway test, then deleted it. Commit 078412b. — If wrong: a test-only binding that is redundant if those fixtures never arrive.
Task 6: review 1 — spec ✅ (money/date formatting still in Java, blocks system-owned, Computation gained subject last, fallback subject intact, TenantContext cleared), quality Approved; 1 Important (null treasury/cui passed raw to accountsBlock while null-guarded for the map — would render "- Beneficiar: null" to a client), 2 Minor.
Task 6: Ruling: folded in the Minor that tightened TaxEmailServiceTest's subject assertion from any(String.class) to eq(...) — the subject now coming from the rendered template is precisely what this task introduced, and it was otherwise unasserted. — If wrong: one assertion tightened a task earlier than triage would have.
Task 6: fix round 1/5 (1 addressed, 1 partially — null guarded inside accountsBlock so all callers are protected, but blank was not, leaving "- Beneficiar:    "; commits 078412b..21aeede)
Task 6: fix round 2/5 (1 addressed, 0 open — collapsed five divergent `== null` ternaries into one private `orEmpty` helper treating null and blank alike; re-reviewer confirmed it was withheld from plain(l)/iban()/explanation() so no money or account value can be silently blanked; commits 21aeede..e8a44f4)
Task 6: controller verified by git at both review points that the three EXPECTED_* golden constants are byte-unchanged across the whole task range — the regression net was not bent.
Task 6: complete (commits a5057aa..e8a44f4, review clean)
Task 7+8: review 1 (one review covering both halves, per the batching ruling) — spec ✅ for Task 7 AND spec ✅ for Task 8, quality Approved; 1 Important, 2 Minor. Reviewer confirmed Ruling B was honoured: all three PayrollServiceTest business-rule tests repointed with assertions intact, including the `[Numele contabilului]` check.
Task 7+8: Important was a genuine regression this task introduced — ReportEmailService.send() fetched the report aggregation twice (once for the PDF/charts, once inside message() purely for the subject), where the old code got its subject from a pure function with no DB access.
Task 7+8: Ruling: folded in the Minor pinning grossProfit's value — it is a client-visible figure in the email and the only one of four left unasserted. Controller independently verified "40 000.00" is right (the fixture sets ProfitLoss.grossProfit explicitly to 40000; the implementer's verbal arithmetic for it was wrong even though the asserted value was correct). — If wrong: one extra assertion on a stored fixture field.
Task 7+8: fix round 1/5 (2 addressed, 0 open — added a message(LocalDate, ReportData) overload so send() renders the subject from the report data it already holds, leaving exactly one aggregation per send; grossProfit value asserted; commits 749d921..3833807). Re-reviewer traced send() line by line to confirm one aggregation, and checked period normalisation is idempotent so a mid-month send still renders the right {{month}}.
Task 7+8: minor (deferred): @Transactional(readOnly = true) on both services' message() is inert when called from their own send() (self-invocation bypasses the proxy) — correct at runtime, but misleading to a reader.
Task 7+8: complete (commits e8a44f4..3833807, review clean)
Task 9: review 1 — spec ✅ but quality **Needs fixes**; 3 Important, 2 Minor. The standout finding was a golden-rule gap: the new endpoint is a new data-access path and had no cross-tenant isolation test, which backend/CLAUDE.md rule #1 mandates.
Task 9: Ruling: folded in the Minor completing locale independence (`new DecimalFormatSymbols(Locale.ROOT)` instead of the default-locale constructor) — this formats client-facing money, and relying on the JVM's default locale for the non-overridden symbols is a portability trap on a differently-configured server. — If wrong: one constructor argument.
Task 9: fix round 1/5 (4 addressed, 0 open — statement check now precedes the transaction query so the no-statement path skips it entirely, locked in by a `verify(..., never())` assertion; cross-tenant isolation test added; Locale.ROOT; commits 0b61496..1159f98). IT genuinely ran under Docker: 3 tests, 0 skips.
Task 9: **Ruling: accepted HTTP 200 on a cross-tenant request rather than requiring 404.** When tenant B's admin requests tenant A's company, RLS makes every lookup come back empty, so the endpoint renders tenant B's own REMINDER_NOTHING_RECEIVED template with an empty company name. The re-reviewer checked every response field (state, subject, body) and every template input and confirmed no tenant A datum reaches the response, and that the response is byte-identical to one for a UUID that never existed — so there is no enumeration oracle. Forcing a 404 would need an explicit existence pre-check, which buys REST tidiness, not security, and ought to be applied consistently across all company-scoped endpoints rather than only this new one. — If wrong: the endpoint answers 200 where 404 would read better; no data exposure either way, and the fix would be a separate, codebase-wide change the user should scope.
Task 9: minor (deferred): DocumentReminderService.send() calls compose() purely for the subject, discarding the state and body; a subjectFor(...) method would avoid that work if the send path ever gets hot.
Task 9: operational note — the implementer hit a stale `.git/worktrees/mf-sdd/index.lock` mid-task (this worktree shares .git with the iCloud-synced checkout, so the known lock pathology still reaches it). Recovered with `-c core.lockTimeout=300`.
Task 9: complete (commits 3833807..1159f98, review clean)
Task 10: review 1 — spec ✅ (all six validation rules enforced AND unit-tested with specific message assertions) but quality **Needs fixes**; 2 Important, 3 Minor. Implementer made a good unforced call: rather than copy-pasting the {{name}} regex into the validator, it exposed TemplateRenderer.TOKEN so there is one copy that cannot drift (reviewer judged this the right seam, and Pattern is immutable so sharing is safe).
Task 10: Ruling: folded both Minors into the fix round — the 301-space subject being rejected as "too long" contradicted the stated rule that a blank subject is legal, and the missing IT-level preview-400 test left an integration gap its PUT counterpart already covered. Both were a few lines inside files already being edited. — If wrong: two small correctness/coverage improvements landed earlier than triage would have.
Task 10: fix round 1/5 (4 addressed, 0 open — REPRESENTATIVE and EMPLOYEE now both asserted 403 on all four endpoints (14 authz tests, all asserting isForbidden() rather than a fuzzy 4xx); SampleTemplateValues.forKind now genuinely filters to kind.allNames(); blankness decided before the subject length cap; IT-level preview-400 test added; commits 00c7f79..acc4302). IT ran under Docker, 14 tests, 0 skips.
Task 10: ⚠️ **the harness raised a generic security warning on the fix subagent** ("auto mode could not evaluate this action"). Controller audited the commit directly: it touches exactly three expected files, changes no authorization annotation, reaches nothing outside the worktree, and the validator reorder moved only the subject length check inside the non-blank branch. The re-reviewer then independently traced all seven validation rules and confirmed none became unreachable, and that nothing weakens a security control. Assessed as a classifier false positive on a shell command (most likely the `git -c core.lockTimeout=300` form the controller itself suggested for the known lock pathology). Recorded rather than silently dismissed.
Task 10: complete (commits 1159f98..acc4302, review clean)
Task 11+12: review 1 (one review, separate per-task verdicts) — spec ✅ for Task 11 AND spec ✅ for Task 12, quality Approved; 2 Important, 3 Minor. Reviewer confirmed the server's validation message does reach the admin (ApiError carries the RFC-7807 `detail`, which is where IllegalArgumentException lands), and that SendReminderModal's cancellation flag and do-not-clobber-an-edited-body guard are both correct.
Task 11+12: Ruling: folded in the Minor where a failed body fetch left a blank textarea and sendAll then silently skipped that company — an admin could believe they had sent N reminders when one never went. The Draft type already had an unused-for-this `error` field and the component already rendered it, so this was a couple of lines reusing existing machinery. — If wrong: one more error path surfaced in a modal.
Task 11+12: **Ruling: parked the second Important — bean-validation errors surfacing as a generic message.** `apiClient` reads only the RFC-7807 `detail` field, while Spring's MethodArgumentNotValidException handler puts field errors in an `errors[]` property, so a `@NotBlank` violation would show "Validation failed" rather than naming the field. Controller verified the only such constraint on these DTOs is `@NotBlank` on `body`, and the editor already disables both Save and Preview when the body is blank — so the path is unreachable from this screen, and the useful `TemplateValidator` messages travel via `detail` and do reach the user. Teaching apiClient to read `errors[]` would change error text for **every form in the application**, which is a cross-cutting change for the user to scope, not something to slip into this feature. — If wrong: a direct API caller (not the UI) posting a blank body sees "Validation failed" instead of a field-specific message; no UI path is affected.
Task 11+12: fix round 1/5 (2 addressed, 0 open — confirm guard on kind switch with a visible "unsaved" indicator so the admin can see what is at risk before clicking away, and a per-company error on failed body fetch; commits 0d27daa..ac87b3e). Re-reviewer traced the decline path and confirmed it is a true no-op: setKind, setDraft, setMsg and setPreview are all skipped, so the select stays put and the typed text survives.
Task 11+12: minor (deferred): the kind-picker's Field label reuses templates.title, which names the whole section rather than the picker; the isLoading branch renders a card containing a literal "…" rather than matching the loading style used elsewhere.
Task 11+12: complete (commits acc4302..ac87b3e, review clean)
Task 13: review 1 — spec ✅, quality Approved; 1 Important (depth), 1 Minor. Reviewer walked all four isolation properties and confirmed each would fail if tenant scoping broke (the tests compare distinctive per-tenant text, not just the `customized` flag), that `asTenant` clears the ThreadLocal in a `finally` so an assertion failure cannot leak the binding, and that per-test random tenant UUIDs make the four tests order-independent on a shared container.
Task 13: fix round 1/5 (2 addressed, 0 open — reset test now asserts tenant B's body IS the built-in PAYROLL default and does NOT contain tenant A's text, rather than trusting the customized flag alone; the misleading "bypasses RLS" seed comment corrected to the real reason, which the implementer traced to V1__baseline.sql's tenant policy permitting a write to the tenant's own row; commits 9c2d03a..fba75d5). IT ran 4/4, 0 skips.
Task 13: complete (commits ac87b3e..fba75d5, review clean)

All 13 tasks complete. Branch has 27 local commits; never pushed, no upstream, no PR — the manual app smoke test (plan Task 13 Step 5) and the push/PR (Step 6) are deliberately left to the user.

## Final whole-branch review (opus, 27 commits, 420KB package, reviewed in six passes)

Verdict: **merge with fixes** — 3 Important, ~12 Minor. It independently agreed with the parked HTTP-200 cross-tenant decision (item 6) and the parked apiClient decision (item 7), and triaged all 8 carried-forward items as can-ship.

Important findings, all genuine and all missed by the per-task reviews:
1. A tenant-controlled **subject could inject a newline into the email `Subject:` header** — the validator allowed `{{signOff}}` in subjects (the spec itself invites it), but SignOff.names returns multi-line text by construction, and the subject reached setSubject() verbatim. First time tenant-controlled text reaches an email header.
2. **`{{totalToPay}}` could mail a client a state-payment total disagreeing with the app's own figure** for the same send — it summed only lines with a configured treasury IBAN, while Computation.total (shown in the in-app notification) sums every payable obligation. Two system-generated figures about money owed to the Romanian state, disagreeing silently.
3. **Nothing structurally guaranteed a declared placeholder is supplied** by its producer — the mirror of Review Focus 4, which the plan had taken seriously only in the other direction.

Ruling: `{{totalToPay}}` carries the **authoritative** total (= Computation.total = the in-app figure), not the sum of listed lines — understating a state liability is the worse error, and two system-generated numbers must never disagree. Residual: when a category lacks a configured IBAN the listed accounts will not sum to the stated total; preventing that is the existing `unconfigured` warning's job. — If wrong: the email's stated total exceeds the sum of the IBANs it lists, which is visible and explicable; the alternative understates what the client owes the state.

Fix wave (one, as the process requires) — commit 5110c19: all three Important findings plus Minors 4/6/8. Scoped re-review: **all findings addressed, no new Critical/Important breakage.**

Controller verification at final HEAD (5110c19), done directly rather than taken on trust:
- Suite read from surefire/failsafe report files: **555 unit** (0 failures, 0 errors, 22 pre-existing fixture-gated skips) + **176 IT** (0 failures, 0 skips).
- The fix-wave report claimed "534 unit", which is simply wrong — 555 is correct and is up 11 from the pre-fix 544, consistent with the tests added. Investigated the apparent drop specifically: **zero `@Test` lines removed** anywhere in the fix, and the only deleted file is the frontend `useFirmName.ts`.
- **Golden wording provably unchanged:** extracted all 47 text-block lines of CurrentMessageWordingTest at Task 3's commit (81852b4) and at final HEAD — identical md5 (5184c808bfcbf6edcb59884e3b222348) and a byte-identical `diff`. My first attempt at this check was wrong (comparing against the merge-base, where the file did not yet exist, and then an md5 of an empty sed match); the corrected check is the one that counts.

Ruling: **parked a residual limitation in ProducerCompletenessTest.** For TAX/REPORT/PAYROLL it calls the real producer, so a dropped `put` fails the test. For the three reminder kinds it asserts against a hand-rolled map mirroring ReminderBodyService.compose rather than invoking the service, so it catches the primary failure mode (a name added to the enum with no supplier anywhere — the mode Important 3 targeted) for all six kinds, but would not catch `compose` losing a `put` while the mirror map kept it. The re-reviewer disclosed this limitation rather than papering over it, and the test's own Javadoc states the trade-off. Not worth a second fix wave, which the process does not allow anyway. — If wrong: the reminder half of that guard is weaker than the email half; the three reminder states remain covered behaviourally by ReminderStateSelectionTest and the endpoint IT.

Also surfaced by the final review but deliberately NOT changed, as a product question for the user: `{{taxDeadline}}` renders "25 \<same month\>", so the April payroll email says taxes are due 25 April — whereas Romanian D112 filing for April salaries is due 25 **May**. This is pre-existing behaviour pinned byte-for-byte by the golden test, so changing it was out of scope and would have broken the regression net on purpose. It now becomes wording a firm can work around in prose rather than something only a deploy can fix.
Task 11+12: Ruling: batching these two frontend tasks into ONE dispatch (two commits) and ONE review. Both are frontend-only, both gated by `tsc --noEmit` + `npm run build`, and both edit `frontend/src/i18n.ts` — 11 adding `templates.*` keys and 12 deleting the now-dead `email.*` body keys — so sequencing them in one agent avoids two passes over the same file. They are less identical in shape than 7+8 were, so the review instructions call for separate per-task verdicts. — If wrong: one review covers both, so a defect in the reminder switch-over could be approved alongside a clean editor UI; the type-check, the build, and the final whole-branch review all still gate it.
Task 11+12: controller established that the frontend builds cleanly WITHOUT `frontend/.env` (tsc clean; Vite+Workbox PWA build succeeded on Node 24.14.1, so the Node-24 Workbox failure noted in project memory does not affect this version). The blocked `.env` copy is therefore moot and the user was not asked for their secrets.
Task 7+8: Ruling: batching these two into ONE dispatch and ONE review. They are the same mechanical shape (builder → values(), service renders, characterization test repointed, composeBody signature preserved), sit in independent modules (reports vs payroll) so they cannot conflict, and are both guarded by the same golden tests — which is the skill's stated criterion for batching same-shape work. Lower risk than Task 6: no money blocks, no record-arity change. — If wrong: one review covers two modules, so a defect in one could be approved alongside the other; the golden net and the final whole-branch review both still gate it.
