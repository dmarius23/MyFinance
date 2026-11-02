# SDD ledger — plan: /Users/marius/mf-sdd/docs/superpowers/plans/2026-11-01-configurable-tax-deadline.md

Executor: inline (superpowers:executing-plans), native per the user's choice.
Spec: docs/superpowers/specs/2026-11-01-configurable-tax-deadline-design.md (reachable, read).
Worktree: /Users/marius/mf-sdd, branch feat/configurable-tax-deadline, base 84a0911
(based off feat/tenant-message-templates — PR #114, open, which carries the plusMonths(1) correction).
Baseline at branch point: 568 unit + 185 IT, 0 failures, 22 pre-existing fixture-gated skips.

## Pre-flight: shared interfaces between tasks

| Tasks | Produces → Consumes | Finding |
|---|---|---|
| 1 → 2 | `PlatformTaxDeadline`, `PlatformTaxDeadlineRepository` (`existsByValidFrom`, `findAllByOrderByValidFromDesc`, plus JpaRepository's `findById`/`save`/`delete`) → admin CRUD | OK — Task 1 declares both custom methods Task 2 needs |
| 1 → 3 | `PlatformTaxDeadlineService.deadlineFor(YearMonth) -> LocalDate` → payroll resolves and passes it in | OK |
| 1 → 4 | same | OK |
| 1 → 5 | same | OK |
| 3 → 4, 3 → 5 | none — independent modules (payroll / taxpayments / dashboard) | no shared interface |
| 2 → 3, 4, 5 | none — admin write surface is not consumed by the read paths | no shared interface |

### Constructor-change blast radius (checked, since a ctor param breaks every direct construction)

- `PlatformReferenceAdminService` — **never constructed directly anywhere**; Spring-wired only. Task 2's
  unit test would therefore be the first direct construction, and its mock burden is unknown until I read
  the constructor. Flagged for a ruling at Task 2 if the burden is disproportionate.
- `PayrollService` — only `PayrollServiceTest`. Matches the plan.
- `DashboardService` — only `DashboardServiceTest`. The plan hedged that `DashboardServiceIT` might
  construct it directly; it does not, so no change is needed there.

## Progress

Task 1: RED confirmed at Step 2 — `cannot find symbol: class PlatformTaxDeadlineService`, matching the brief's Expected.
Task 1: Ruling: could not run the planned mutation experiment to prove the two Review Focus tests can fail — the harness blocked both an in-place `sed` and an `Edit` that would deliberately introduce a bug into the service (a fair thing for a safety classifier to stop), and I did not work around it. Established the discriminating power by reading the tests instead: `theRuleIsSelectedByThePeriodNotByToday` stubs ONLY 2026-04-01 and 2027-04-01, so a now-based implementation (today is 2026-11-01) hits an unstubbed argument, receives null, and NPEs on `.map()`; `queriesTheRepositoryWithThePeriodsFirstDay` asserts the exact argument via `verify`, which a now-based call fails outright; the fallback test stubs `any()` to empty, which `orElseThrow` would turn into a throw rather than 25. All three are decisive by construction. — If wrong: the tests are weaker than claimed and a period-vs-now regression could slip past this task; Task 3's end-to-end body assertion and the final review are the remaining nets.
Task 1: note — JVM/Mockito warnings (sun.misc.Unsafe deprecation, Mockito self-attaching agent, CDS sharing) appear in every test run in this repo on JDK 25 against a Java 21 target. Pre-existing toolchain noise, not introduced here.
Task 1: complete (commits 84a0911..0cab330, tests: mvn -q -f backend/pom.xml -Dtest=PlatformTaxDeadlineServiceTest test → 18:28:46.988 [main] WARN ro.myfinance.settings.application.PlatformTaxDeadlineService -- No salary-tax deadline configured at or before 2026-04 — using day 25)
Task 1: (task-done captured a log line rather than the count — actual figures: PlatformTaxDeadlineServiceTest 8/8 pass; full unit suite 576 run, 0 failures, 0 errors, 22 pre-existing skips, EXIT=0, 0 failing report files. The fallback WARN visible in that run is incidental proof the empty-table test exercises the fallback branch rather than passing vacuously.)

Task 2: pre-flight unknown resolved — PlatformReferenceAdminService takes only two repositories, so the unit test's mock burden is three. No ruling needed; the plan's unit test stands.
Task 2: RED confirmed at Step 2 — `cannot find symbol: method addTaxDeadline(short,LocalDate)` plus the expected constructor-arity mismatch.
Task 2: Ruling: used the IT file's existing FUTURE=2099 convention for the new deadline rows instead of the plan's 2027 dates, and extended cleanup() to delete deadline rows with year >= 2090. A leftover 2027 row would become the rule in force for any later test resolving a 2027 period — for this table specifically, a stale row changes other tests' answers rather than merely existing. Far-future rows cannot. — If wrong: the IT's dates differ cosmetically from the plan's text; the assertions are identical.
Task 2: my own defect, caught by reading EXIT=1 rather than the test counts — the first Edit to PlatformReferenceDtos.java was rejected ("file has not been read yet") and I added the records without retrying the import, so the build failed on `cannot find symbol: PlatformTaxDeadline`. The stale report still showed the pre-change count of 6, which is exactly how this would have been missed by trusting counts alone. Added the import; re-run gave EXIT=0 and 10 tests.
Task 2: PlatformReferenceControllerIT now runs 10 tests (6 pre-existing + 4 new), 0 failures, 46s under Testcontainers — confirmed by name from the failsafe XML, since the .txt reports only name tests on failure. The isolation-test substitute `aNonSuperAdminCannotReadOrWriteTaxDeadlines` is among them.
Task 2: Ruling (user-directed): switched the per-task verification from the plan's full `mvn -q test`/`verify` steps to TARGETED test runs, with ONE full verify before the final review. The full suite takes ~33 min because `verify` starts real Postgres containers for ~50 IT classes, so five full runs would cost over two hours of waiting. Stopped the in-flight Task 2 verify (it was at 109 unit + 50 IT reports, 0 failures) since Task 2's targeted runs already passed. — If wrong: a regression in a module no task touches surfaces at the end-of-run verify rather than at the task that caused it; with five small, well-isolated tasks that is an acceptable trade, and the completion contract still requires the targeted tests the brief names.
Task 2: complete (commits 0cab330..HEAD, tests: PlatformTaxDeadlineAdminTest 6/6 + PlatformReferenceControllerIT 10/10 under Docker, all four new tests confirmed by name in the failsafe XML)
Task 3: RED confirmed at Step 2 — `method values ... cannot be applied to given types; required: (LocalDate,String,String,String); found: (...,LocalDate)`, matching the brief.
Task 3: ten call sites across the five files the plan named (PayrollTemplateWiringTest x3, CurrentMessageWordingTest x2, PayrollServiceTest x4, ProducerCompletenessTest x1, PayrollService x1).
Task 3: **EXPECTED_PAYROLL_BODY byte-unchanged, proven not assumed** — extracted all 47 pinned text-block lines before (7363f50) and after, identical md5 67c0ea87086e2adb4cdd39ff632da280 and a clean `diff`. The only edits to that file are the APRIL_DEADLINE constant and two call sites.
Task 3: complete (commits 7363f50..HEAD, tests: CurrentMessageWordingTest 6/6, DefaultMessageTemplatesTest 7/7, PayrollTemplateWiringTest 4/4, PayrollServiceTest 5/5, ProducerCompletenessTest 6/6 — all targeted per the verification switch)
Task 4: RED confirmed at Step 2 — `constructor AnafDeclarationExtractor cannot be applied to given types; required: no arguments`.
Task 4: Ruling: put ONE `TestTaxDeadlines.seeded()` factory in the existing `ro.myfinance.support` test package instead of the plan's four per-file copies of the same five-line mock. The plan's reason for duplicating was "these files are in three different packages", but `ro.myfinance.support` is already the shared test-support home imported by 51 test files across packages, so the objection doesn't hold and four copies would be duplication a reviewer would rightly flag. — If wrong: one small test-support class exists that four private helpers would have covered.
Task 4: **plan defect found — `defaultDeadline` has FOUR callers, not the one the plan claimed.** Lines 123 (parseD112), 152 (a TVA obligation), and 258/263 inside `parseDate(String, YearMonth)` — the generic fallback used whenever ANY declaration's scadenta is missing or unparseable. Surfaced as a compile error ("non-static method ... cannot be referenced from a static context") rather than silently. Ruling: made `parseDate` non-static, the smallest change — its only caller (`parseD100`) was already an instance method, so the cascade stopped there. This BROADENS the feature favourably: D100's missing-scadenta fallback is now configurable too, which the spec's intent ("the statutory deadline when the XML carries no explicit scadenta") covers. — If wrong: a private helper lost `static` and one more declaration type reads the configured day; the spec wanted exactly that.
Task 4: Ruling: wrote the new D112 test against inline XML via `parseXmlBytes` instead of the brief's fixture-gated `extractFixture("D112.pdf")`. The ANAF PDFs are gitignored PII, absent from every clone, so the brief's version would ALWAYS skip — and a skipped test proves nothing about the wiring it exists to check. Confirmed from the failsafe XML that it RAN while the three fixture-dependent tests skipped. — If wrong: the test exercises a hand-built minimal D112 document rather than a real one; the three real-fixture tests still cover authentic parsing when the PDFs are present.
Task 4: my own error, caught by reading the test error rather than the pass count — I guessed the D112 root element as `declaratie112`; the dispatcher expects `declaratieUnica`. Fixed in the test, not the production code.
Task 4: complete (commits d5c34f1..HEAD, tests: AnafDeclarationExtractorTest 5 run/0 fail (3 fixture-gated skips), TaxPaymentServiceTest 3/0, TaxDeclarationServiceTest 3/0, TaxDeclarationListenerTest 5/0, DocumentValidatorTest 14/0)
Task 5: RED confirmed at Step 2 — DashboardService constructor arity mismatch.
Task 5: DashboardServiceIT does NOT construct the service directly (confirmed in pre-flight), so no change was needed there, as predicted.
Task 5: Step 5 grep over backend/src/main/java for `withDayOfMonth(25)`, `atDay(25)` and `"25 "` returns NOTHING — all three consumers read the configured value, and the only surviving 25 is the named PlatformTaxDeadlineService.DEFAULT_DAY fallback.
Task 5: complete (commits bb4c1e4..HEAD, tests: DashboardServiceTest 5/5)
