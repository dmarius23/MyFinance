# Configurable Salary-Tax Deadline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the salary-tax deadline day super-admin-managed, effective-dated reference data, so a statutory change is a new row instead of a deploy — and the three places that each compute it today read one source.

**Architecture:** A global `platform_tax_deadline` table (no `tenant_id`, no RLS — public national reference data, mirroring `platform_tax_rate`) holds an effective-dated day-of-month. `PlatformTaxDeadlineService` resolves it by **payroll period** ("greatest `valid_from <= period` wins") and returns the deadline as `period.plusMonths(1).atDay(day)`; the `+1` offset stays in code. Four endpoints on the existing `PlatformReferenceController` manage it under its class-level `SUPER_ADMIN` gate. Three consumers are rewired to call the service.

**Tech Stack:** Java 21, Spring Boot 3, Spring Data JPA, Flyway, PostgreSQL, JUnit 5 + AssertJ + Mockito + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-01-configurable-tax-deadline-design.md` — read it before Task 1. Section numbers below (§3, §5…) refer to it.

## Global Constraints

- **Migration is `V63__platform_tax_deadline.sql`.** V62 is the latest existing. Never edit an applied migration.
- **This table is deliberately GLOBAL: no `tenant_id`, no RLS.** It is a documented exception to golden rule #1, pre-authorised by `backend/docs/MyFinance-global-reference-settings-design-v1.md` §4 for public reference data. The migration comment must say so explicitly, and the mandatory cross-tenant isolation test is replaced by the authz test in Task 2. Do not add `tenant_id` or an RLS policy.
- **`CHECK (day_of_month BETWEEN 1 AND 28)`** — a day of 29–31 would silently shift in February. Do not widen it to 31.
- **Seed `(25, '2020-01-01')`** in the migration so every existing period resolves and no consumer needs a special case.
- **Effective dating is selected by the payroll PERIOD, not by `LocalDate.now()`.** Re-sending March's email in December must restate March's rule. Passing `now()` into `dayFor`/`deadlineFor` is a defect.
- **Only the day is configurable.** The `+1` month offset stays hardcoded; an editable offset of `0` would silently restore the bug where April's email claimed 25 April.
- **`CurrentMessageWordingTest` must stay byte-identical.** The seeded day 25 reproduces today's `"25 Mai 2026"` for an April period. If a golden assertion fails, the seed or the wiring is wrong — never the expectation. There is no instruction anywhere in this plan to edit `EXPECTED_PAYROLL_BODY`.
- **Exception mapping:** there is **no `BadRequestException`** in this codebase. `ApiExceptionHandler` maps `IllegalArgumentException` → HTTP 400. Duplicates use the existing `ConflictException` (as `addTaxRate` does). Missing rows use `NotFoundException`.
- **No PII in logs.** The fallback warning names the period only.
- **No new dependencies.**
- **Build:** `cd backend && mvn -q test` (unit), `mvn -q verify` (adds `*IT`; Docker is available, so ITs must actually run, not skip). Baseline at branch point: 568 unit + 185 IT, 0 failures, 22 pre-existing fixture-gated skips.

## Review Focus

Five failure modes the spec implies whose tests are easy to omit. Each has a test assigned to the task that owns the code.

1. **Re-sending an old period's email after a rule change restates the NEW rule.** The single most likely defect: resolving with `LocalDate.now()` instead of the period. A March email re-sent in December must still say March's deadline. → Task 1, Step 9.
2. **The seeded row gets deleted and every resolution breaks.** The admin API can delete the only row; the table is then empty. Resolution must fall back to 25 and warn, not throw mid-send. → Task 1, Step 7.
3. **A future `valid_from` changes today's answers.** An admin schedules "day 20 from 2027-01-01"; every 2026 period must still resolve to 25. → Task 1, Step 9.
4. **A configured day of 28 with a January period.** Resolves to 28 February, which exists in every year — this is why the `CHECK` stops at 28. → Task 1, Step 11.
5. **A non-SUPER_ADMIN reaching the admin endpoints.** All four must return 403 — including `GET`, since the whole controller is SUPER_ADMIN-only. → Task 2, Step 7.

---

## File Structure

**Created**

| File | Responsibility |
|---|---|
| `resources/db/migration/V63__platform_tax_deadline.sql` | Table, `CHECK`, seed, grants, and the written rationale for having no RLS |
| `settings/domain/PlatformTaxDeadline.java` | Entity; day editable in place, `valid_from` immutable |
| `settings/adapter/persistence/PlatformTaxDeadlineRepository.java` | Effective-dated lookup + admin listing + duplicate guard |
| `settings/application/PlatformTaxDeadlineService.java` | `dayFor` / `deadlineFor`, fallback 25 with a warning |
| `test/.../settings/PlatformTaxDeadlineServiceTest.java` | Effective dating, fallback, February edge, period-not-now |

**Modified**

| File | Change |
|---|---|
| `settings/application/PlatformReferenceAdminService.java` | `listTaxDeadlines` / `addTaxDeadline` / `updateTaxDeadline` / `deleteTaxDeadline` |
| `settings/adapter/web/PlatformReferenceDtos.java` | `TaxDeadlineResponse`, `CreateTaxDeadlineRequest`, `UpdateTaxDeadlineRequest` |
| `settings/adapter/web/PlatformReferenceController.java` | Four `/tax-deadlines` endpoints |
| `test/.../settings/PlatformReferenceControllerIT.java` | SUPER_ADMIN CRUD + non-SUPER_ADMIN 403 on all four |
| `payroll/application/PayrollEmailBuilder.java` | `values(...)` takes a trailing `LocalDate taxDeadline` |
| `payroll/application/PayrollService.java:201` | `message(...)` resolves and passes the deadline |
| `taxpayments/application/AnafDeclarationExtractor.java:47` | Injects the service; `defaultDeadline` delegates |
| `dashboard/application/DashboardService.java:72` | Uses `deadlineFor` instead of inline `withDayOfMonth(25)` |
| 5 test files constructing `new AnafDeclarationExtractor()` | Pass a stub service |

---

## Task 1: Reference data and resolution

**Files:**
- Create: `backend/src/main/resources/db/migration/V63__platform_tax_deadline.sql`
- Create: `backend/src/main/java/ro/myfinance/settings/domain/PlatformTaxDeadline.java`
- Create: `backend/src/main/java/ro/myfinance/settings/adapter/persistence/PlatformTaxDeadlineRepository.java`
- Create: `backend/src/main/java/ro/myfinance/settings/application/PlatformTaxDeadlineService.java`
- Test: `backend/src/test/java/ro/myfinance/settings/PlatformTaxDeadlineServiceTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `PlatformTaxDeadline(short dayOfMonth, LocalDate validFrom)` with `getId()`, `getDayOfMonth()`, `setDayOfMonth(short)`, `getValidFrom()`, `getCreatedAt()`.
  - `PlatformTaxDeadlineRepository.findTopByValidFromLessThanEqualOrderByValidFromDesc(LocalDate)` → `Optional<PlatformTaxDeadline>`; `findAllByOrderByValidFromDesc()` → `List<PlatformTaxDeadline>`; `existsByValidFrom(LocalDate)` → `boolean`.
  - `PlatformTaxDeadlineService.dayFor(YearMonth period)` → `int`; `PlatformTaxDeadlineService.deadlineFor(YearMonth period)` → `LocalDate`.
  - `PlatformTaxDeadlineService.DEFAULT_DAY` → `int` constant `25`.

- [ ] **Step 1: Write the failing resolution test**

Create `backend/src/test/java/ro/myfinance/settings/PlatformTaxDeadlineServiceTest.java`:

```java
package ro.myfinance.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ro.myfinance.settings.adapter.persistence.PlatformTaxDeadlineRepository;
import ro.myfinance.settings.application.PlatformTaxDeadlineService;
import ro.myfinance.settings.domain.PlatformTaxDeadline;

/**
 * The salary-tax deadline is the configured day of the month AFTER the period, resolved by the rule in
 * force during that PERIOD — not the rule in force today. That distinction is what lets an old month's
 * email be re-sent without silently restating a newer statute.
 */
class PlatformTaxDeadlineServiceTest {

    private final PlatformTaxDeadlineRepository repo = mock(PlatformTaxDeadlineRepository.class);
    private final PlatformTaxDeadlineService service = new PlatformTaxDeadlineService(repo);

    private void givenRule(short day, String validFrom) {
        when(repo.findTopByValidFromLessThanEqualOrderByValidFromDesc(any()))
                .thenReturn(Optional.of(new PlatformTaxDeadline(day, LocalDate.parse(validFrom))));
    }

    @Test
    void resolvesTheConfiguredDayInTheMonthAfterThePeriod() {
        givenRule((short) 25, "2020-01-01");
        assertThat(service.dayFor(YearMonth.of(2026, 4))).isEqualTo(25);
        assertThat(service.deadlineFor(YearMonth.of(2026, 4))).isEqualTo(LocalDate.of(2026, 5, 25));
    }

    @Test
    void aChangedDayMovesTheDeadlineWithinTheSameFollowingMonth() {
        givenRule((short) 20, "2026-01-01");
        assertThat(service.deadlineFor(YearMonth.of(2026, 4))).isEqualTo(LocalDate.of(2026, 5, 20));
    }

    @Test
    void decemberRollsIntoTheFollowingYear() {
        givenRule((short) 25, "2020-01-01");
        assertThat(service.deadlineFor(YearMonth.of(2026, 12))).isEqualTo(LocalDate.of(2027, 1, 25));
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd backend && mvn -q -Dtest=PlatformTaxDeadlineServiceTest test`
Expected: FAIL — `cannot find symbol: class PlatformTaxDeadlineService`.

- [ ] **Step 3: Write the migration**

Create `backend/src/main/resources/db/migration/V63__platform_tax_deadline.sql`:

```sql
-- The statutory day-of-month on which salary taxes (D112) fall due, in the month FOLLOWING the period.
-- Effective-dated: the greatest valid_from <= period wins, so a statutory change never retroactively
-- alters an earlier period.
--
-- GLOBAL reference data: deliberately NO tenant_id and NO row-level security. This is identical public
-- Romanian reference data for every tenant, exactly like platform_tax_rate and platform_treasury_account
-- (see V35) — so it is a DOCUMENTED EXCEPTION to golden rule #1 ("every tenant table has tenant_id + RLS
-- + a cross-tenant isolation test"), not an oversight. Writes are gated at the application layer by
-- @PreAuthorize("hasRole('SUPER_ADMIN')") on PlatformReferenceController; a shared DB role cannot
-- distinguish SUPER_ADMIN, so authz lives in the app as it does for the sibling reference tables. The
-- mandatory isolation test is replaced by an authz test asserting a non-SUPER_ADMIN gets 403.
CREATE TABLE platform_tax_deadline (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    day_of_month smallint NOT NULL CHECK (day_of_month BETWEEN 1 AND 28),
    valid_from   date NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_platform_tax_deadline UNIQUE (valid_from)
);

-- 1..28 rather than 1..31: a day of 29-31 would silently move in February. If a statute ever names the
-- 30th, that needs an explicit end-of-month clamping rule, decided deliberately.

-- Seed the rule in force today so every existing period resolves and no consumer needs a special case.
INSERT INTO platform_tax_deadline (day_of_month, valid_from) VALUES (25, '2020-01-01');

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON platform_tax_deadline TO myfinance_app;
    END IF;
END $$;
```

- [ ] **Step 4: Add the entity and repository**

Create `backend/src/main/java/ro/myfinance/settings/domain/PlatformTaxDeadline.java`:

```java
package ro.myfinance.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * The statutory day-of-month on which salary taxes fall due, in the month following the period.
 * Effective-dated. GLOBAL reference data — no {@code tenant_id}, no RLS (see V63). Managed by
 * SUPER_ADMIN; tenants read only.
 *
 * <p>Only the day is stored. The "month following the period" offset is intrinsic to monthly filing and
 * stays in code: a configurable offset of zero would silently claim April's taxes are due in April.
 */
@Entity
@Table(name = "platform_tax_deadline")
public class PlatformTaxDeadline {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "day_of_month", nullable = false)
    private short dayOfMonth;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PlatformTaxDeadline() {
    }

    public PlatformTaxDeadline(short dayOfMonth, LocalDate validFrom) {
        this.dayOfMonth = dayOfMonth;
        this.validFrom = validFrom;
    }

    /** The day is editable in place; the effective date is immutable (add a new row instead). */
    public void setDayOfMonth(short dayOfMonth) { this.dayOfMonth = dayOfMonth; }

    public UUID getId() { return id; }
    public short getDayOfMonth() { return dayOfMonth; }
    public LocalDate getValidFrom() { return validFrom; }
    public Instant getCreatedAt() { return createdAt; }
}
```

Create `backend/src/main/java/ro/myfinance/settings/adapter/persistence/PlatformTaxDeadlineRepository.java`:

```java
package ro.myfinance.settings.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.settings.domain.PlatformTaxDeadline;

/** Global salary-tax deadline reference data — no tenant scoping (these rows are national/public). */
public interface PlatformTaxDeadlineRepository extends JpaRepository<PlatformTaxDeadline, UUID> {

    /** Effective-dated lookup: the rule in force for {@code period} = greatest valid_from <= period. */
    Optional<PlatformTaxDeadline> findTopByValidFromLessThanEqualOrderByValidFromDesc(LocalDate period);

    /** Full history for the admin screen, newest effective date first. */
    List<PlatformTaxDeadline> findAllByOrderByValidFromDesc();

    /** Guards valid_from uniqueness at the app layer for a friendly conflict message. */
    boolean existsByValidFrom(LocalDate validFrom);
}
```

- [ ] **Step 5: Implement the service**

Create `backend/src/main/java/ro/myfinance/settings/application/PlatformTaxDeadlineService.java`:

```java
package ro.myfinance.settings.application;

import java.time.LocalDate;
import java.time.YearMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.settings.adapter.persistence.PlatformTaxDeadlineRepository;
import ro.myfinance.settings.domain.PlatformTaxDeadline;

/**
 * Period-aware resolver for the salary-tax deadline. GLOBAL, not tenant-scoped (see V63).
 *
 * <p>The rule is chosen by the PERIOD, not by today's date: re-sending March's payroll email in December
 * must restate March's deadline, not whatever statute is current. Callers therefore pass the payroll
 * period — passing {@code YearMonth.now()} would defeat the effective dating.
 */
@Service
@Transactional(readOnly = true)
public class PlatformTaxDeadlineService {

    /** Used when no row is configured at or before the period. The statute in force as of 2026. */
    public static final int DEFAULT_DAY = 25;

    private static final Logger log = LoggerFactory.getLogger(PlatformTaxDeadlineService.class);

    private final PlatformTaxDeadlineRepository deadlines;

    public PlatformTaxDeadlineService(PlatformTaxDeadlineRepository deadlines) {
        this.deadlines = deadlines;
    }

    /**
     * The statutory day-of-month in force for {@code period}. Falls back to {@link #DEFAULT_DAY} rather
     * than throwing or returning empty: a missing row must not fail a payroll send mid-flight, and three
     * consumers each inventing their own fallback is how the drift this table exists to remove restarts.
     */
    public int dayFor(YearMonth period) {
        if (period == null) {
            return DEFAULT_DAY;
        }
        return deadlines.findTopByValidFromLessThanEqualOrderByValidFromDesc(period.atDay(1))
                .map(PlatformTaxDeadline::getDayOfMonth)
                .map(Short::intValue)
                .orElseGet(() -> {
                    log.warn("No salary-tax deadline configured at or before {} — using day {}",
                            period, DEFAULT_DAY);
                    return DEFAULT_DAY;
                });
    }

    /** The deadline date: the configured day of the month AFTER {@code period}. */
    public LocalDate deadlineFor(YearMonth period) {
        YearMonth due = (period == null ? YearMonth.now() : period).plusMonths(1);
        return due.atDay(dayFor(period));
    }
}
```

- [ ] **Step 6: Run the resolution test to verify it passes**

Run: `cd backend && mvn -q -Dtest=PlatformTaxDeadlineServiceTest test`
Expected: PASS (3 tests).

- [ ] **Step 7: Add the empty-table fallback test (Review Focus 2)**

Append to `PlatformTaxDeadlineServiceTest`:

```java
    /**
     * Review Focus 2: the admin API can delete the seeded row, leaving the table empty. Resolution must
     * fall back rather than throw — a missing row must never fail a payroll send mid-flight.
     */
    @Test
    void anEmptyTableFallsBackToDayTwentyFiveRatherThanThrowing() {
        when(repo.findTopByValidFromLessThanEqualOrderByValidFromDesc(any()))
                .thenReturn(Optional.empty());
        assertThat(service.dayFor(YearMonth.of(2026, 4))).isEqualTo(25);
        assertThat(service.deadlineFor(YearMonth.of(2026, 4))).isEqualTo(LocalDate.of(2026, 5, 25));
    }

    @Test
    void aNullPeriodFallsBackInsteadOfThrowing() {
        assertThat(service.dayFor(null)).isEqualTo(25);
    }
```

- [ ] **Step 8: Run to verify**

Run: `cd backend && mvn -q -Dtest=PlatformTaxDeadlineServiceTest test`
Expected: PASS (5 tests).

- [ ] **Step 9: Add the period-not-now and future-rule tests (Review Focus 1 and 3)**

These two are the heart of effective dating. Append:

```java
    /**
     * Review Focus 1 + 3: the rule is chosen by the PERIOD, not by today. A rule effective 2027 must not
     * touch a 2026 period, and re-resolving an old period must still give the old answer. This is the
     * test that fails if someone resolves with YearMonth.now().
     */
    @Test
    void theRuleIsSelectedByThePeriodNotByToday() {
        // Two rules: 25 from 2020, 20 from 2027. The repository is asked with the PERIOD's first day,
        // so a 2026 period must be matched against the 2020 rule.
        when(repo.findTopByValidFromLessThanEqualOrderByValidFromDesc(LocalDate.of(2026, 4, 1)))
                .thenReturn(Optional.of(new PlatformTaxDeadline((short) 25, LocalDate.of(2020, 1, 1))));
        when(repo.findTopByValidFromLessThanEqualOrderByValidFromDesc(LocalDate.of(2027, 4, 1)))
                .thenReturn(Optional.of(new PlatformTaxDeadline((short) 20, LocalDate.of(2027, 1, 1))));

        assertThat(service.deadlineFor(YearMonth.of(2026, 4))).isEqualTo(LocalDate.of(2026, 5, 25));
        assertThat(service.deadlineFor(YearMonth.of(2027, 4))).isEqualTo(LocalDate.of(2027, 5, 20));
    }

    @Test
    void queriesTheRepositoryWithThePeriodsFirstDay() {
        givenRule((short) 25, "2020-01-01");
        service.dayFor(YearMonth.of(2026, 4));
        org.mockito.Mockito.verify(repo)
                .findTopByValidFromLessThanEqualOrderByValidFromDesc(LocalDate.of(2026, 4, 1));
    }
```

- [ ] **Step 10: Run to verify**

Run: `cd backend && mvn -q -Dtest=PlatformTaxDeadlineServiceTest test`
Expected: PASS (7 tests).

- [ ] **Step 11: Add the February edge test (Review Focus 4)**

Append:

```java
    /**
     * Review Focus 4: day 28 is the highest the CHECK permits, precisely so February can never shorten
     * a deadline. A January period therefore resolves into February without clamping.
     */
    @Test
    void dayTwentyEightWithAJanuaryPeriodResolvesIntoFebruary() {
        givenRule((short) 28, "2020-01-01");
        assertThat(service.deadlineFor(YearMonth.of(2026, 1))).isEqualTo(LocalDate.of(2026, 2, 28));
        // And in a leap year, still the 28th — the rule is a fixed day, not "end of month".
        assertThat(service.deadlineFor(YearMonth.of(2028, 1))).isEqualTo(LocalDate.of(2028, 2, 28));
    }
```

- [ ] **Step 12: Run the full suite**

Run: `cd backend && mvn -q test`
Expected: PASS. The new table is additive and nothing consumes it yet, so no existing test should move.

- [ ] **Step 13: Commit**

```bash
git add backend/src/main/resources/db/migration/V63__platform_tax_deadline.sql \
        backend/src/main/java/ro/myfinance/settings/domain/PlatformTaxDeadline.java \
        backend/src/main/java/ro/myfinance/settings/adapter/persistence/PlatformTaxDeadlineRepository.java \
        backend/src/main/java/ro/myfinance/settings/application/PlatformTaxDeadlineService.java \
        backend/src/test/java/ro/myfinance/settings/PlatformTaxDeadlineServiceTest.java
git commit -m "feat(settings): add effective-dated salary-tax deadline reference data

Global table mirroring platform_tax_rate: no tenant_id, no RLS, as the
global-reference design doc prescribes for public national data. The rule
is resolved by the payroll PERIOD, not today, so re-sending an old
month's email cannot restate a newer statute. Seeded with day 25 so every
existing period resolves unchanged."
```

---

## Task 2: Admin endpoints and the authorization test

This task carries the test that **substitutes for the mandatory cross-tenant isolation test**. The table has no `tenant_id`, so there is nothing to isolate; what must be proven instead is that only a SUPER_ADMIN can touch it.

**Files:**
- Modify: `backend/src/main/java/ro/myfinance/settings/application/PlatformReferenceAdminService.java`
- Modify: `backend/src/main/java/ro/myfinance/settings/adapter/web/PlatformReferenceDtos.java`
- Modify: `backend/src/main/java/ro/myfinance/settings/adapter/web/PlatformReferenceController.java`
- Test: `backend/src/test/java/ro/myfinance/settings/PlatformReferenceControllerIT.java`

**Interfaces:**
- Consumes: `PlatformTaxDeadline`, `PlatformTaxDeadlineRepository` (Task 1).
- Produces:
  - `PlatformReferenceAdminService.listTaxDeadlines()` → `List<PlatformTaxDeadline>`
  - `PlatformReferenceAdminService.addTaxDeadline(short dayOfMonth, LocalDate validFrom)` → `PlatformTaxDeadline`
  - `PlatformReferenceAdminService.updateTaxDeadline(UUID id, short dayOfMonth)` → `PlatformTaxDeadline`
  - `PlatformReferenceAdminService.deleteTaxDeadline(UUID id)` → `void`
  - DTOs `TaxDeadlineResponse(UUID id, short dayOfMonth, LocalDate validFrom)`, `CreateTaxDeadlineRequest(@NotNull Short dayOfMonth, @NotNull LocalDate validFrom)`, `UpdateTaxDeadlineRequest(@NotNull Short dayOfMonth)`
  - Endpoints `GET|POST /api/v1/admin/reference/tax-deadlines`, `PUT|DELETE /api/v1/admin/reference/tax-deadlines/{id}`

- [ ] **Step 1: Write the failing admin-service test**

Create `backend/src/test/java/ro/myfinance/settings/PlatformTaxDeadlineAdminTest.java`:

```java
package ro.myfinance.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.settings.adapter.persistence.PlatformTaxDeadlineRepository;
import ro.myfinance.settings.domain.PlatformTaxDeadline;

/**
 * Write-side validation for the salary-tax deadline. The day is constrained to 1..28 in the database
 * too, but rejecting it here gives the admin a readable message instead of a constraint violation.
 */
class PlatformTaxDeadlineAdminTest {

    private final PlatformTaxDeadlineRepository repo = mock(PlatformTaxDeadlineRepository.class);
    // Construct PlatformReferenceAdminService with its real collaborators; pass mocks for the tax-rate
    // and treasury repositories it already takes, and `repo` for the new one. Read its constructor and
    // mirror the existing argument order, appending the new repository last.

    @Test
    void acceptsADayWithinRange() {
        when(repo.existsByValidFrom(LocalDate.of(2027, 1, 1))).thenReturn(false);
        when(repo.save(org.mockito.ArgumentMatchers.any(PlatformTaxDeadline.class)))
                .thenAnswer(i -> i.getArgument(0));
        var saved = service().addTaxDeadline((short) 20, LocalDate.of(2027, 1, 1));
        assertThat(saved.getDayOfMonth()).isEqualTo((short) 20);
        assertThat(saved.getValidFrom()).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void rejectsADayBelowOne() {
        assertThatThrownBy(() -> service().addTaxDeadline((short) 0, LocalDate.of(2027, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 and 28");
    }

    @Test
    void rejectsADayAboveTwentyEight() {
        assertThatThrownBy(() -> service().addTaxDeadline((short) 31, LocalDate.of(2027, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 and 28");
    }

    @Test
    void rejectsAMissingEffectiveDate() {
        assertThatThrownBy(() -> service().addTaxDeadline((short) 25, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Effective date");
    }

    @Test
    void rejectsADuplicateEffectiveDate() {
        when(repo.existsByValidFrom(LocalDate.of(2027, 1, 1))).thenReturn(true);
        assertThatThrownBy(() -> service().addTaxDeadline((short) 20, LocalDate.of(2027, 1, 1)))
                .isInstanceOf(ro.myfinance.common.web.ConflictException.class);
    }

    @Test
    void updatingAMissingRowIsNotFound() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().updateTaxDeadline(id, (short) 20))
                .isInstanceOf(ro.myfinance.common.web.NotFoundException.class);
    }
}
```

Write the `service()` helper that builds `PlatformReferenceAdminService` with mocks for its existing repositories plus `repo`. Read the existing constructor first and append the new repository as the **last** parameter so existing call sites read naturally.

- [ ] **Step 2: Run to confirm it fails**

Run: `cd backend && mvn -q -Dtest=PlatformTaxDeadlineAdminTest test`
Expected: FAIL — `cannot find symbol: method addTaxDeadline`.

- [ ] **Step 3: Add the admin-service methods**

In `PlatformReferenceAdminService`, add `PlatformTaxDeadlineRepository deadlines` as the last constructor parameter and field, then add beside the tax-rate methods:

```java
    // ---- salary-tax deadline --------------------------------------------------------------------

    public List<PlatformTaxDeadline> listTaxDeadlines() {
        return deadlines.findAllByOrderByValidFromDesc();
    }

    /** Add an effective-dated deadline day. One row per valid_from. */
    public PlatformTaxDeadline addTaxDeadline(short dayOfMonth, LocalDate validFrom) {
        if (validFrom == null) {
            throw new IllegalArgumentException("Effective date is required");
        }
        requireDayOfMonth(dayOfMonth);
        if (deadlines.existsByValidFrom(validFrom)) {
            throw new ConflictException("A salary-tax deadline effective " + validFrom + " already exists");
        }
        return deadlines.save(new PlatformTaxDeadline(dayOfMonth, validFrom));
    }

    /** Edit the day of an existing row (the effective date stays fixed — add a new row instead). */
    public PlatformTaxDeadline updateTaxDeadline(UUID id, short dayOfMonth) {
        requireDayOfMonth(dayOfMonth);
        PlatformTaxDeadline d = deadlines.findById(id)
                .orElseThrow(() -> new NotFoundException("Salary-tax deadline not found: " + id));
        d.setDayOfMonth(dayOfMonth);
        return d;
    }

    public void deleteTaxDeadline(UUID id) {
        PlatformTaxDeadline d = deadlines.findById(id)
                .orElseThrow(() -> new NotFoundException("Salary-tax deadline not found: " + id));
        deadlines.delete(d);
    }

    /**
     * 1..28 mirrors the database CHECK. The ceiling is 28 rather than 31 so a deadline can never shift
     * in February; a statute naming the 30th would need an explicit end-of-month rule.
     */
    private static void requireDayOfMonth(short dayOfMonth) {
        if (dayOfMonth < 1 || dayOfMonth > 28) {
            throw new IllegalArgumentException("Deadline day must be between 1 and 28");
        }
    }
```

- [ ] **Step 4: Run to verify the admin test passes**

Run: `cd backend && mvn -q -Dtest=PlatformTaxDeadlineAdminTest test`
Expected: PASS (6 tests).

- [ ] **Step 5: Add the DTOs**

In `PlatformReferenceDtos`, beside the tax-rate records:

```java
    public record TaxDeadlineResponse(UUID id, short dayOfMonth, LocalDate validFrom) {
        public static TaxDeadlineResponse from(PlatformTaxDeadline d) {
            return new TaxDeadlineResponse(d.getId(), d.getDayOfMonth(), d.getValidFrom());
        }
    }

    public record CreateTaxDeadlineRequest(@NotNull Short dayOfMonth, @NotNull LocalDate validFrom) {
    }

    public record UpdateTaxDeadlineRequest(@NotNull Short dayOfMonth) {
    }
```

`Short` (boxed) rather than `short` so `@NotNull` can catch an omitted field instead of silently defaulting to 0.

- [ ] **Step 6: Add the four endpoints**

In `PlatformReferenceController`, after the tax-rate block:

```java
    // ---- salary-tax deadline --------------------------------------------------------------------

    @GetMapping("/tax-deadlines")
    public List<TaxDeadlineResponse> listTaxDeadlines() {
        return service.listTaxDeadlines().stream().map(TaxDeadlineResponse::from).toList();
    }

    @PostMapping("/tax-deadlines")
    @ResponseStatus(HttpStatus.CREATED)
    public TaxDeadlineResponse addTaxDeadline(@Valid @RequestBody CreateTaxDeadlineRequest r) {
        return TaxDeadlineResponse.from(service.addTaxDeadline(r.dayOfMonth(), r.validFrom()));
    }

    @PutMapping("/tax-deadlines/{id}")
    public TaxDeadlineResponse updateTaxDeadline(@PathVariable UUID id,
                                                 @Valid @RequestBody UpdateTaxDeadlineRequest r) {
        return TaxDeadlineResponse.from(service.updateTaxDeadline(id, r.dayOfMonth()));
    }

    @DeleteMapping("/tax-deadlines/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTaxDeadline(@PathVariable UUID id) {
        service.deleteTaxDeadline(id);
    }
```

Add no method-level `@PreAuthorize` — the class-level `hasRole('SUPER_ADMIN')` already governs these, and a redundant annotation invites someone to "adjust" one copy later.

- [ ] **Step 7: Extend the IT — CRUD plus the authorization substitute (Review Focus 5)**

In `PlatformReferenceControllerIT`, reuse the existing `superAdmin()` and `tenantAdmin()` JWT helpers and add:

```java
    @Test
    void superAdminCreatesListsEditsAndDeletesATaxDeadline() throws Exception {
        String created = mvc.perform(post("/api/v1/admin/reference/tax-deadlines").with(superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayOfMonth\":20,\"validFrom\":\"2027-01-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dayOfMonth").value(20))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        mvc.perform(get("/api/v1/admin/reference/tax-deadlines").with(superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.validFrom == '2027-01-01')].dayOfMonth")
                        .value(org.hamcrest.Matchers.hasItem(20)));

        mvc.perform(put("/api/v1/admin/reference/tax-deadlines/{id}", id).with(superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dayOfMonth\":22}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayOfMonth").value(22));

        mvc.perform(delete("/api/v1/admin/reference/tax-deadlines/{id}", id).with(superAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void anOutOfRangeDayIsRejectedWithFourHundred() throws Exception {
        mvc.perform(post("/api/v1/admin/reference/tax-deadlines").with(superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayOfMonth\":31,\"validFrom\":\"2027-02-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aDuplicateEffectiveDateIsRejected() throws Exception {
        String body = "{\"dayOfMonth\":20,\"validFrom\":\"2027-03-01\"}";
        mvc.perform(post("/api/v1/admin/reference/tax-deadlines").with(superAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/admin/reference/tax-deadlines").with(superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    /**
     * This is the substitute for the mandatory cross-tenant isolation test: the table has no tenant_id,
     * so there is nothing to isolate — what must hold is that only a SUPER_ADMIN can reach it. All four
     * endpoints, GET included, since the whole controller is SUPER_ADMIN-only.
     */
    @Test
    void aNonSuperAdminCannotReadOrWriteTaxDeadlines() throws Exception {
        mvc.perform(get("/api/v1/admin/reference/tax-deadlines").with(tenantAdmin()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/reference/tax-deadlines").with(tenantAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayOfMonth\":20,\"validFrom\":\"2027-04-01\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/reference/tax-deadlines/{id}", UUID.randomUUID())
                        .with(tenantAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayOfMonth\":20}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/reference/tax-deadlines/{id}", UUID.randomUUID())
                        .with(tenantAdmin()))
                .andExpect(status().isForbidden());
    }
```

Check how the existing tests clean up between methods (there is a `cleanup()` method) and make sure your added rows are removed the same way, so `superAdminCreatesListsEditsAndDeletesATaxDeadline` cannot leave a 2027 rule behind that changes another test's resolution.

- [ ] **Step 8: Run the IT**

Run: `cd backend && mvn -q -Dtest=PlatformReferenceControllerIT verify`
Expected: PASS, and the IT must actually RUN (Docker is available) — report the test count and confirm nothing skipped.

- [ ] **Step 9: Run the full suite**

Run: `cd backend && mvn -q verify`
Expected: PASS, no new failures.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/ro/myfinance/settings/ \
        backend/src/test/java/ro/myfinance/settings/
git commit -m "feat(settings): manage the salary-tax deadline as SUPER_ADMIN

Four endpoints on the existing reference controller, inheriting its
SUPER_ADMIN gate. The authz test asserting a non-SUPER_ADMIN gets 403 on
all four — GET included — is the deliberate substitute for the usual
cross-tenant isolation test, which does not apply to a table with no
tenant_id. Day validated to 1..28 at the app layer for a readable
message as well as in the CHECK."
```

---

## Task 3: Payroll consumer — the client-visible one

This is the only consumer whose output a client reads, so the golden net guards it. **`CurrentMessageWordingTest.EXPECTED_PAYROLL_BODY` must not be edited in this task.** The seeded day 25 reproduces `"25 Mai 2026"` for an April period; if the golden assertion fails, the wiring is wrong.

**Files:**
- Modify: `backend/src/main/java/ro/myfinance/payroll/application/PayrollEmailBuilder.java`
- Modify: `backend/src/main/java/ro/myfinance/payroll/application/PayrollService.java:201`
- Modify: `backend/src/test/java/ro/myfinance/messaging/PayrollTemplateWiringTest.java`
- Modify: `backend/src/test/java/ro/myfinance/messaging/CurrentMessageWordingTest.java` (call sites only)
- Modify: `backend/src/test/java/ro/myfinance/messaging/ProducerCompletenessTest.java` (call site only)
- Modify: `backend/src/test/java/ro/myfinance/payroll/PayrollServiceTest.java`

**Interfaces:**
- Consumes: `PlatformTaxDeadlineService.deadlineFor(YearMonth)` (Task 1).
- Produces: `PayrollEmailBuilder.values(LocalDate period, String companyName, String senderName, String firmName, LocalDate taxDeadline)` → `Map<String, String>`. The `taxDeadline` parameter is **trailing** and **required**; there is no overload, so no call site can silently fall back to a hardcoded day.

- [ ] **Step 1: Write the failing builder test**

In `PayrollTemplateWiringTest`, replace `valuesCarryTheMonthAndTheFollowingMonthsTwentyFifthDeadline` and `decemberDeadlineRollsIntoTheFollowingYear` with versions that pass the deadline in, and add one proving a different configured day flows through:

```java
    /** The deadline is supplied by the caller (resolved from reference data), formatted here. */
    @Test
    void valuesFormatTheSuppliedDeadline() {
        var values = PayrollEmailBuilder.values(LocalDate.of(2026, 4, 17), "Acme SRL",
                "Maria Popescu", "ContaZone SRL", LocalDate.of(2026, 5, 25));
        assertThat(values.get("month")).isEqualTo("Aprilie 2026");
        assertThat(values.get("taxDeadline")).isEqualTo("25 Mai 2026");
        assertThat(values.get("companyName")).isEqualTo("Acme SRL");
        assertThat(values.get("signOff")).isEqualTo("Maria Popescu\nContaZone SRL");
    }

    /** A changed statutory day reaches the client's email unchanged in shape. */
    @Test
    void aDifferentConfiguredDayFlowsThrough() {
        var values = PayrollEmailBuilder.values(LocalDate.of(2026, 4, 1), "Acme SRL",
                null, "Firma", LocalDate.of(2026, 5, 20));
        assertThat(values.get("taxDeadline")).isEqualTo("20 Mai 2026");
    }

    /** December's payroll is due in January of the NEXT year — the year must travel with the month. */
    @Test
    void decemberDeadlineRollsIntoTheFollowingYear() {
        var values = PayrollEmailBuilder.values(LocalDate.of(2026, 12, 31), "Acme SRL",
                null, "Firma", LocalDate.of(2027, 1, 25));
        assertThat(values.get("month")).isEqualTo("Decembrie 2026");
        assertThat(values.get("taxDeadline")).isEqualTo("25 Ianuarie 2027");
    }
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd backend && mvn -q -Dtest=PayrollTemplateWiringTest test`
Expected: FAIL — no `values(...)` overload taking five arguments.

- [ ] **Step 3: Change the builder**

In `PayrollEmailBuilder`, add the trailing parameter and format the supplied date. The day comes from the date itself, so the method no longer knows anything about "25":

```java
    /**
     * Everything a {@code PAYROLL} template may reference. {@code taxDeadline} is resolved by the caller
     * from effective-dated reference data ({@code PlatformTaxDeadlineService}) and merely formatted here
     * — the builder stays a pure variable-map assembler with no injected dependency, which is also what
     * keeps the golden wording test deterministic without a database.
     */
    public static Map<String, String> values(LocalDate period, String companyName,
                                             String senderName, String firmName,
                                             LocalDate taxDeadline) {
        LocalDate p = period.withDayOfMonth(1);
        Map<String, String> v = new HashMap<>();
        v.put("companyName", companyName == null ? "" : companyName);
        v.put("month", monthYear(p));
        v.put("taxDeadline", taxDeadline == null ? ""
                : taxDeadline.getDayOfMonth() + " " + monthYear(taxDeadline));
        v.put("signOff", ro.myfinance.common.email.SignOff.names(senderName, firmName));
        return v;
    }
```

Update the class Javadoc: the deadline is no longer "always the 25th of the month following" but "resolved from effective-dated reference data by the caller; the month-following offset is intrinsic and stays in code". Keep the note that this email previously named the period's own month.

- [ ] **Step 4: Run to verify the builder test passes**

Run: `cd backend && mvn -q -Dtest=PayrollTemplateWiringTest test`
Expected: PASS (4 tests, including the retained `normalizesAnyDayOfMonthToTheFirst` — give it the deadline argument too).

- [ ] **Step 5: Wire `PayrollService`**

Add `PlatformTaxDeadlineService deadlines` as a constructor parameter and field, then:

```java
    /** Subject + body for a company/period, rendered from this tenant's PAYROLL template. */
    @Transactional(readOnly = true)
    public RenderedMessage message(UUID companyId, LocalDate period) {
        LocalDate deadline = deadlines.deadlineFor(YearMonth.from(period));
        return templates.render(MessageTemplateKind.PAYROLL, PayrollEmailBuilder.values(
                period, companyName(companyId), envelopes.currentUserName(), envelopes.firmName(),
                deadline));
    }
```

Pass the **period**, never `YearMonth.now()` — that is Review Focus 1 and the whole point of the effective dating.

- [ ] **Step 6: Fix the remaining call sites**

The compiler will name them. Three test files call `values(...)` and need the deadline argument:

- `CurrentMessageWordingTest` — `renderPayroll(...)` and `payrollSubjectIsUnchanged` pass `LocalDate.of(2026, 5, 25)` (the April period's deadline under the seeded rule). **Do not touch `EXPECTED_PAYROLL_BODY`.**
- `ProducerCompletenessTest` — pass any valid date, e.g. `LocalDate.of(2026, 5, 25)`; this test only checks which keys exist.
- `PayrollServiceTest` — `composesStandardRomanianBody` passes `LocalDate.of(2026, 5, 25)` and keeps asserting `"până în data de 25 Mai 2026"`. Its service-construction tests need the new `PlatformTaxDeadlineService` argument: pass a mock whose `deadlineFor(any())` returns `LocalDate.of(2026, 5, 25)`.

- [ ] **Step 7: Run the golden net plus payroll tests**

Run: `cd backend && mvn -q -Dtest=CurrentMessageWordingTest,DefaultMessageTemplatesTest,PayrollTemplateWiringTest,PayrollServiceTest,ProducerCompletenessTest test`
Expected: PASS. If `CurrentMessageWordingTest` fails, the wiring or the passed date is wrong — fix that, never the expectation. If you believe the expectation itself is wrong, stop and report BLOCKED.

- [ ] **Step 8: Add an end-to-end test that a changed rule reaches the rendered body**

In `PayrollServiceTest`, add:

```java
    /**
     * The client-visible payoff: a changed statutory day reaches the rendered email body. Asserting the
     * body rather than the map is deliberate — this is the text a client reads.
     */
    @Test
    void aChangedDeadlineRuleReachesTheRenderedBody() {
        var values = PayrollEmailBuilder.values(LocalDate.of(2026, 4, 1), null, "Maria Pop",
                "ContaZone SRL", LocalDate.of(2026, 5, 20));
        String body = new TemplateRenderer().render(
                DefaultMessageTemplates.of(MessageTemplateKind.PAYROLL).body(), values);
        assertThat(body).contains("până în data de 20 Mai 2026");
        assertThat(body).doesNotContain("25 Mai 2026");
    }
```

- [ ] **Step 9: Run the full suite**

Run: `cd backend && mvn -q test`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/ro/myfinance/payroll/ \
        backend/src/test/java/ro/myfinance/messaging/ \
        backend/src/test/java/ro/myfinance/payroll/
git commit -m "feat(payroll): resolve the email's tax deadline from reference data

PayrollService resolves the deadline for the PERIOD and passes it into
the builder, which stays a static, dependency-free variable-map
assembler. No overload is kept, so no call site can silently fall back
to a hardcoded 25. The golden wording test is untouched and still green:
the seeded day 25 reproduces '25 Mai 2026' byte for byte."
```

---

## Task 4: D112 extractor consumer

**Files:**
- Modify: `backend/src/main/java/ro/myfinance/taxpayments/application/AnafDeclarationExtractor.java:47`
- Modify: `backend/src/test/java/ro/myfinance/taxpayments/AnafDeclarationExtractorTest.java`
- Modify: `backend/src/test/java/ro/myfinance/taxpayments/TaxPaymentServiceTest.java:88`
- Modify: `backend/src/test/java/ro/myfinance/taxpayments/TaxDeclarationServiceTest.java:33`
- Modify: `backend/src/test/java/ro/myfinance/taxpayments/TaxDeclarationListenerTest.java:39`
- Modify: `backend/src/test/java/ro/myfinance/intake/DocumentValidatorTest.java:43`

**Interfaces:**
- Consumes: `PlatformTaxDeadlineService.deadlineFor(YearMonth)` (Task 1).
- Produces: `AnafDeclarationExtractor(PlatformTaxDeadlineService deadlines)` — the no-arg constructor is **removed**, deliberately: an overload defaulting to 25 would let a forgotten dependency silently rebuild the drift this work removes.

- [ ] **Step 1: Write the failing test**

In `AnafDeclarationExtractorTest`, the extractor is currently a field initialised with `new AnafDeclarationExtractor()`. Replace it with a stub-backed instance and add a test proving the configured day is used:

```java
    private final PlatformTaxDeadlineService deadlines = mock(PlatformTaxDeadlineService.class);
    private final AnafDeclarationExtractor extractor = new AnafDeclarationExtractor(deadlines);

    @BeforeEach
    void stubDeadline() {
        // Mirrors the seeded rule so every existing fixture assertion keeps its current expectation.
        when(deadlines.deadlineFor(any(YearMonth.class)))
                .thenAnswer(i -> ((YearMonth) i.getArgument(0)).plusMonths(1).atDay(25));
    }

    /** D112 carries no per-line scadenta, so the configured statutory day is what gets stamped. */
    @Test
    void d112ObligationsTakeTheConfiguredDeadlineDay() throws Exception {
        byte[] d112 = fixture("D112.pdf");
        assumeTrue(d112 != null, "ANAF fixtures missing (PII, gitignored)");
        when(deadlines.deadlineFor(any(YearMonth.class)))
                .thenAnswer(i -> ((YearMonth) i.getArgument(0)).plusMonths(1).atDay(20));

        var parsed = extractor.extract(d112);
        assertThat(parsed.obligations()).isNotEmpty();
        assertThat(parsed.obligations()).allSatisfy(o ->
                assertThat(o.scadenta().getDayOfMonth()).isEqualTo(20));
    }
```

Read the existing test to match its `fixture(...)` helper and the accessor names on `ParsedDeclaration` / `TaxObligation` (the field may be `scadenta()` or `deadline()` — use what the record actually declares).

- [ ] **Step 2: Run to confirm it fails**

Run: `cd backend && mvn -q -Dtest=AnafDeclarationExtractorTest test`
Expected: FAIL — no constructor taking `PlatformTaxDeadlineService`.

- [ ] **Step 3: Inject the service**

In `AnafDeclarationExtractor`, add the constructor dependency and delegate:

```java
    private final PlatformTaxDeadlineService deadlines;

    public AnafDeclarationExtractor(PlatformTaxDeadlineService deadlines) {
        this.deadlines = deadlines;
    }

    /**
     * The statutory deadline for a period, when the declaration XML carries no explicit scadenta (D112).
     * Resolved from effective-dated reference data rather than hardcoded, so a statutory change does not
     * need a deploy — and cannot leave this value disagreeing with the payroll email or the dashboard,
     * which is exactly what happened before.
     */
    private LocalDate defaultDeadline(YearMonth period) {
        return deadlines.deadlineFor(period);
    }
```

`defaultDeadline` stops being `static`. Leave `parseD112`'s call site and its comment as they are.

- [ ] **Step 4: Run to verify**

Run: `cd backend && mvn -q -Dtest=AnafDeclarationExtractorTest test`
Expected: PASS. The fixture-gated test skips without the gitignored PII fixtures; the constructor change still has to compile.

- [ ] **Step 5: Fix the four remaining constructions**

Each of these builds the extractor directly. Give each a mock service stubbed to the seeded rule, so their existing assertions keep their current expectations:

```java
    private static PlatformTaxDeadlineService seededDeadlines() {
        PlatformTaxDeadlineService d = mock(PlatformTaxDeadlineService.class);
        when(d.deadlineFor(any(YearMonth.class)))
                .thenAnswer(i -> ((YearMonth) i.getArgument(0)).plusMonths(1).atDay(25));
        return d;
    }
```

- `TaxPaymentServiceTest:88` → `new AnafDeclarationExtractor(seededDeadlines())`
- `TaxDeclarationServiceTest:33` → same
- `TaxDeclarationListenerTest:39` → same
- `DocumentValidatorTest:43` → same

Add the helper to each file rather than inventing a shared test utility — four short private methods are clearer here than a new cross-package base class, and these files are in three different packages.

- [ ] **Step 6: Run the full suite**

Run: `cd backend && mvn -q verify`
Expected: PASS. Several of these tests are fixture-gated and will skip; they must still compile and the non-gated ones must pass.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/ro/myfinance/taxpayments/application/AnafDeclarationExtractor.java \
        backend/src/test/java/ro/myfinance/taxpayments/ \
        backend/src/test/java/ro/myfinance/intake/DocumentValidatorTest.java
git commit -m "feat(taxpayments): resolve the D112 scadenta fallback from reference data

The no-arg constructor is removed on purpose: an overload defaulting to
25 would let a forgotten dependency silently restore the disagreement
between this value, the payroll email and the dashboard. Four test
constructions updated with a stub matching the seeded rule."
```

---

## Task 5: Dashboard consumer and whole-feature verification

**Files:**
- Modify: `backend/src/main/java/ro/myfinance/dashboard/application/DashboardService.java:42,72`
- Modify: `backend/src/test/java/ro/myfinance/dashboard/DashboardServiceTest.java`
- Modify: `backend/src/test/java/ro/myfinance/dashboard/DashboardServiceIT.java` (construction only, if it builds the service directly)

**Interfaces:**
- Consumes: `PlatformTaxDeadlineService.deadlineFor(YearMonth)` (Task 1).
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

In `DashboardServiceTest`, add a test that the overdue flag follows the configured rule. Read the existing test first to see how it builds the service and what it asserts on; the point is that a day late enough to be in the future must make `overdue` zero:

```java
    /**
     * The overdue count is gated on the configured deadline, not a hardcoded 25. Note this consumer
     * compares the resolved deadline against TODAY — "has this period's filing window closed?" — while
     * the rule itself is still selected by the period.
     */
    @Test
    void overdueFollowsTheConfiguredDeadlineRatherThanAHardcodedDay() {
        // A deadline far in the future for the period under test => nothing is overdue yet.
        when(deadlines.deadlineFor(any(YearMonth.class))).thenReturn(LocalDate.now().plusDays(5));
        assertThat(overdueCountFor(MONTH)).isZero();

        // A deadline already past => an incomplete tax status counts as overdue.
        when(deadlines.deadlineFor(any(YearMonth.class))).thenReturn(LocalDate.now().minusDays(1));
        assertThat(overdueCountFor(MONTH)).isPositive();
    }
```

Write `overdueCountFor(...)` against whatever the service actually returns (read the existing assertions for the shape of the summary object and which field carries the overdue tally). If the existing test has no fixture producing a non-DONE tax status, add one — otherwise the second assertion cannot be positive and the test proves nothing.

- [ ] **Step 2: Run to confirm it fails**

Run: `cd backend && mvn -q -Dtest=DashboardServiceTest test`
Expected: FAIL — no `deadlines` collaborator on the service.

- [ ] **Step 3: Wire `DashboardService`**

Add `PlatformTaxDeadlineService deadlines` as the last constructor parameter and field, then replace line 72:

```java
        // Tax obligations are overdue once the configured statutory deadline for the period has passed.
        // The rule is chosen by the period; whether it has elapsed is judged against today.
        boolean taxDeadlinePassed = LocalDate.now().isAfter(deadlines.deadlineFor(YearMonth.from(month)));
```

- [ ] **Step 4: Run to verify**

Run: `cd backend && mvn -q -Dtest=DashboardServiceTest test`
Expected: PASS. Fix `DashboardServiceIT`'s construction if it builds the service directly.

- [ ] **Step 5: Confirm no hardcoded deadline survives**

Run:
```bash
cd /Users/marius/mf-sdd && grep -rn "withDayOfMonth(25)\|atDay(25)\|\"25 \"" backend/src/main/java
```
Expected: no matches in `backend/src/main/java`. `PlatformTaxDeadlineService.DEFAULT_DAY = 25` is a named constant and is fine; a bare `25` arithmetic in a consumer is not. If anything remains, it is a consumer this plan missed — report it.

- [ ] **Step 6: Full verification**

Run: `cd backend && mvn -q verify`
Expected: PASS. Baseline was 568 unit + 185 IT with 22 pre-existing fixture-gated skips; the count should rise by the tests this plan adds. Confirm by name that these are green: `PlatformTaxDeadlineServiceTest`, `PlatformTaxDeadlineAdminTest`, `PlatformReferenceControllerIT`, `CurrentMessageWordingTest`, `DefaultMessageTemplatesTest`, `PayrollTemplateWiringTest`, `ProducerCompletenessTest`.

Run: `cd frontend && npx tsc --noEmit && npm run build`
Expected: clean. No frontend change is intended; this only proves nothing broke.

- [ ] **Step 7: Verify the seeded rule reproduces today's behaviour end to end**

With Docker up, confirm the migration applies and the seed resolves: `PlatformReferenceControllerIT` exercises the table through Flyway, so a green run proves `V63` applies cleanly on top of `V62`. Also confirm there is exactly one `V63` and no duplicate versions:

```bash
cd /Users/marius/mf-sdd && ls backend/src/main/resources/db/migration | sed 's/__.*//' | sort | uniq -d
```
Expected: no output.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/ro/myfinance/dashboard/ \
        backend/src/test/java/ro/myfinance/dashboard/
git commit -m "feat(dashboard): gate the overdue count on the configured deadline

Replaces the last inline withDayOfMonth(25). All three consumers now
resolve from one effective-dated source, so the drift that had the
payroll email claiming April while the dashboard used May is no longer
expressible."
```

---

## Self-Review

**1. Spec coverage**

| Spec section | Task |
|---|---|
| §3 data model, `CHECK` 1..28, seed, no-RLS rationale | 1 |
| §4 resolution, `dayFor`/`deadlineFor`, fallback 25 + warn | 1 |
| §5 payroll consumer (value passed in, builder stays pure) | 3 |
| §5 extractor consumer (injected, no-arg removed) | 4 |
| §5 dashboard consumer | 5 |
| §6 four admin endpoints, validation, no frontend | 2 |
| §7 effective-dating tests | 1 (steps 9, 11) |
| §7 read-allowed / write-denied substitute for the isolation test | 2 (step 7) |
| §7 fallback test | 1 (step 7) |
| §7 February edge | 1 (step 11) |
| §7 golden regression byte-identical | 3 (step 7) |
| §7 consumer wiring tests | 3 (step 8), 4 (step 1), 5 (step 1) |
| §8 out of scope | respected — no per-type deadlines, no configurable offset, no frontend, no per-tenant override |

No gaps.

**2. Placeholders** — none. Every code step carries real code. Four steps deliberately say "read the existing file first" rather than inventing a signature: `PlatformReferenceAdminService`'s constructor argument order (Task 2 Step 1), `ParsedDeclaration`/`TaxObligation`'s accessor name for the deadline (Task 4 Step 1), `DashboardServiceTest`'s summary shape and overdue field (Task 5 Step 1), and `AnafDeclarationExtractorTest`'s `fixture(...)` helper (Task 4 Step 1). Those are shapes a test must mirror exactly, and guessing them would produce confidently wrong code.

**3. Type consistency** — `dayOfMonth` is `short` on the entity and in the service/admin signatures, boxed to `Short` only in the request DTOs so `@NotNull` can catch omission; `dayFor` returns `int` (widened once, in the service) and `deadlineFor` returns `LocalDate`. `PayrollEmailBuilder.values` gains a trailing `LocalDate taxDeadline` and all five call sites are enumerated in Task 3 Steps 5–6. `AnafDeclarationExtractor`'s only constructor takes `PlatformTaxDeadlineService`, and all five constructions are enumerated in Task 4. `ConflictException` for duplicates and `NotFoundException` for missing rows match `addTaxRate`/`updateTaxRate`; `IllegalArgumentException` → 400 via `ApiExceptionHandler`.

**4. Review Focus coverage** — all five have an owning task and step: period-not-now (1.9), empty table (1.7), future `valid_from` (1.9), day 28 + January (1.11), non-SUPER_ADMIN 403 on all four (2.7).

One residual risk worth stating rather than burying: **Task 3 Step 7 is where a tired implementer could do real damage** by "fixing" `EXPECTED_PAYROLL_BODY` instead of the wiring. The step says explicitly to report BLOCKED instead, and Task 3's commit message asserts the constant is untouched — so a reviewer can check the claim against the diff.

