# Configurable salary-tax deadline — design

**Date:** 2026-11-01
**Status:** approved design, ready for implementation planning
**Branch base:** `feat/tenant-message-templates` (PR #114, open) — it carries the `plusMonths(1)`
correction this work builds on.
**Modules touched:** `settings` (owns the reference data), `payroll`, `taxpayments`, `dashboard`.

## 1. Purpose

The salary-tax deadline — "the 25th of the month following the payroll period" — is a hardcoded constant
in three places that agree only by coincidence:

| Where | What it drives |
|---|---|
| `PayrollEmailBuilder.values` | the `{{taxDeadline}}` a client reads in the payroll email |
| `AnafDeclarationExtractor.defaultDeadline` | the `scadenta` stamped on D112 obligations when the XML carries none |
| `DashboardService` | `taxDeadlinePassed`, which gates the per-company **overdue** count |

They have already drifted once. Until commit 38da8a9 the payroll email used the period's *own* month, so
it told clients April salary taxes were due 25 April while the dashboard and the extractor used 25 May.
That was fixed by correcting the arithmetic in place; the duplication remained.

After this change the day is **super-admin-managed, effective-dated reference data** and all three
consumers resolve it from one source. A statutory change becomes a new row, not a deploy.

**Success criteria.** A SUPER_ADMIN adds a row saying the day is 20 from a given date; payroll emails,
D112 obligation deadlines and the dashboard's overdue check all move together, and periods before that
date still resolve to 25. A deployment that adds no row behaves exactly as today.

## 2. Decisions

| Decision | Rationale |
|---|---|
| **One deadline, not one per declaration type** | All three consumers use the same value today. `defaultDeadline` is used in exactly one place — `parseD112` ("D112 carries no per-line scadenta"); D300 and D100 carry their own. Per-type deadlines only become meaningful alongside the parked quarterly-D300 / D100-due-months cadence work, and building them now would build half of that. |
| **Only the day is configurable; the `+1` month offset stays in code** | Statutory changes move the day (25 → 20), not the structure — "month following the period" is intrinsic to monthly filing. An editable offset would also re-introduce the exact footgun just fixed: an offset of `0` silently restores "April taxes due 25 April". |
| **A dedicated `platform_tax_deadline` table** | Mirrors `platform_tax_rate`. Rejected: reusing `platform_tax_rate` with a synthetic `PAYROLL_DEADLINE_DAY` category, which would store a calendar day in a `numeric(6,2)` column named `rate` and force every `TaxRateCategory` consumer to learn that one member is not a rate. Also rejected: a general `platform_setting(key, value)` table — untyped, per-key ad-hoc validation, a settings framework built to hold one integer. |
| **Global: no `tenant_id`, no RLS** | Identical Romanian reference data for every tenant, per `backend/docs/MyFinance-global-reference-settings-design-v1.md` §4. Writes gated app-layer by `@PreAuthorize("hasRole('SUPER_ADMIN')")`. |
| **The resolved value is passed into `PayrollEmailBuilder`, not injected** | The template work established that builders assemble a variable map and services do the resolving. Keeping the builder static and dependency-free also keeps `CurrentMessageWordingTest` deterministic without a database. |
| **`AnafDeclarationExtractor` injects the service** | Rejected a no-arg overload defaulting to 25, which would mean far less churn — but a production call site that forgot the parameter would silently get 25 and rebuild the drift this work removes. Five mechanical test-construction edits are the better trade. |

## 3. Data model

`V63__platform_tax_deadline.sql`:

```sql
-- The statutory day-of-month on which salary taxes (D112) fall due, in the month FOLLOWING the period.
-- Effective-dated: the greatest valid_from <= period wins, so historical periods keep their own rule.
--
-- GLOBAL reference data: deliberately NO tenant_id and NO row-level security. This is identical public
-- Romanian reference data for every tenant, exactly like platform_tax_rate and platform_treasury_account
-- — see backend/docs/MyFinance-global-reference-settings-design-v1.md §4. It is therefore a documented
-- exception to golden rule #1 ("every tenant table has tenant_id + RLS + a cross-tenant isolation
-- test"), NOT an oversight. Writes are gated at the app layer by @PreAuthorize("hasRole('SUPER_ADMIN')");
-- a shared DB role cannot distinguish SUPER_ADMIN, so authz lives in the application as it does for the
-- sibling reference tables. The mandatory isolation test is replaced by a read-allowed / write-denied
-- test (§6).
CREATE TABLE platform_tax_deadline (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    day_of_month smallint NOT NULL CHECK (day_of_month BETWEEN 1 AND 28),
    valid_from   date NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_platform_tax_deadline UNIQUE (valid_from)
);

-- Seed the rule in force today so every existing period resolves and no consumer needs a special case.
INSERT INTO platform_tax_deadline (day_of_month, valid_from) VALUES (25, '2020-01-01');

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON platform_tax_deadline TO myfinance_app;
    END IF;
END $$;
```

**Why `1..28` and not `1..31`:** a day of 29–31 would silently shift in February, producing a deadline
that differs by month for no stated reason. If a statute ever names the 30th, that needs an explicit
end-of-month clamping rule introduced deliberately — not a `CHECK` that quietly permits it.

## 4. Resolution

`settings/domain/PlatformTaxDeadline.java` — entity, mirroring `PlatformTaxRate`.

`settings/adapter/persistence/PlatformTaxDeadlineRepository.java`:

```java
Optional<PlatformTaxDeadline> findTopByValidFromLessThanEqualOrderByValidFromDesc(LocalDate period);
List<PlatformTaxDeadline> findAllByOrderByValidFromDesc();
```

`settings/application/PlatformTaxDeadlineService.java`:

```java
/** The statutory day-of-month, for the rule in force during {@code period}. Defaults to 25. */
public int dayFor(YearMonth period)

/** The deadline date itself: the configured day of the month AFTER the period. */
public LocalDate deadlineFor(YearMonth period)
```

`deadlineFor` is `period.plusMonths(1).atDay(dayFor(period))`. The `+1` is deliberately not configurable.

**Fallback.** With no matching row, `dayFor` returns **25** and logs a warning naming the period. The
sibling `PlatformRatesService.rateFor` returns `Optional.empty()` and lets callers decide, but a missing
deadline must not fail a payroll send mid-flight, and propagating emptiness into three consumers would
give each one its own fallback to get wrong. A seeded row plus one hard default is safer.

The effective-dating semantics are "greatest `valid_from <= period` wins", where `period` is the payroll
period — **not** today's date. Re-sending March's payroll email in December must restate March's rule.

## 5. Consumers

**`PayrollService.message`** resolves the deadline and passes it in:

```java
public RenderedMessage message(UUID companyId, LocalDate period) {
    LocalDate deadline = deadlines.deadlineFor(YearMonth.from(period));
    return templates.render(MessageTemplateKind.PAYROLL, PayrollEmailBuilder.values(
            period, companyName(companyId), envelopes.currentUserName(), envelopes.firmName(), deadline));
}
```

`PayrollEmailBuilder.values(...)` takes a trailing `LocalDate taxDeadline` and formats it as
`"25 " + monthYear(...)` exactly as now — the formatting stays in Java, and the builder stays static with
no injected dependency.

**`AnafDeclarationExtractor`** gains a constructor dependency on `PlatformTaxDeadlineService`;
`defaultDeadline(YearMonth)` becomes `deadlines.deadlineFor(period)`. Five test files construct it with
`new AnafDeclarationExtractor()` and must pass a stub returning 25:
`AnafDeclarationExtractorTest`, `TaxPaymentServiceTest`, `TaxDeclarationServiceTest`,
`TaxDeclarationListenerTest`, `DocumentValidatorTest`.

**`DashboardService`** gains the same dependency; the inline
`LocalDate.now().isAfter(month.plusMonths(1).withDayOfMonth(25))` becomes
`LocalDate.now().isAfter(deadlines.deadlineFor(YearMonth.from(month)))`. Note this consumer compares the
resolved deadline against **today**, which is correct — "has this period's filing window closed?" — even
though the rule itself is selected by period.

## 6. Admin surface

Four endpoints added to the existing `PlatformReferenceController`
(`/api/v1/admin/reference`, class-level `@PreAuthorize("hasRole('SUPER_ADMIN')")`), mirroring the
tax-rate shape exactly:

| Method | Path |
|---|---|
| `GET` | `/tax-deadlines` |
| `POST` | `/tax-deadlines` → 201 |
| `PUT` | `/tax-deadlines/{id}` |
| `DELETE` | `/tax-deadlines/{id}` → 204 |

Request/response records go in `PlatformReferenceDtos`; the service methods go on
`PlatformReferenceAdminService` beside `addTaxRate` / `updateTaxRate` / `deleteTaxRate`. No new
controller and no new authorization surface.

**Validation on write:** `day_of_month` within 1–28 (rejected with HTTP 400 via
`IllegalArgumentException`, which `ApiExceptionHandler` maps), and `valid_from` required. The unique
constraint on `valid_from` surfaces a duplicate as a conflict rather than silently shadowing a row.

**No frontend work.** `/admin/tenants` is a placeholder route today; this is API-only, consistent with
how the sibling reference data is managed.

## 7. Testing

Per the global-reference design doc, the usual cross-tenant isolation test does not apply — the table has
no `tenant_id`. It is replaced by:

- **Read-allowed / write-denied IT.** Tenant staff (`TENANT_ADMIN`, `EMPLOYEE`) can resolve a deadline
  through the consuming paths; a non-`SUPER_ADMIN` gets **403** on all four write endpoints. This is the
  substitute the design doc explicitly prescribes, and the migration comment plus the PR body say so, so
  it is not mistaken for a golden-rule violation.
- **Effective-dating unit tests:** a period before the earliest row, a period exactly on a `valid_from`
  boundary, a period after a later row, and two rows where the later must win.
- **Fallback test:** an empty table resolves to 25 and logs a warning rather than throwing.
- **February edge:** a January period resolves to 25 February; a configured day of 28 with a January
  period resolves to 28 February. (A day above 28 is rejected by the `CHECK`, so no month-length
  surprise is reachable.)
- **Golden regression:** `CurrentMessageWordingTest` must stay **byte-identical**. The seeded day 25
  reproduces today's "25 Mai 2026" for an April period. If it moves, the seed or the wiring is wrong —
  never the expectation.
- **Consumer wiring tests:** each of the three consumers reflects a changed configured day. The payroll
  one asserts the rendered body, since that is the text a client receives.

## 8. Out of scope

- **Per-declaration-type deadlines** (D100 / D300 cadence) — see §2; entangled with the parked tax-cadence
  refinement.
- **A configurable month offset** — see §2; the `+1` stays in code.
- **Any frontend** — API-only, matching the sibling reference data.
- **Per-tenant overrides** — this is national statute, identical for every firm.
- **Back-dating historical sends** — changing a row does not retroactively alter already-sent emails, which
  are recorded with the body that went out.
