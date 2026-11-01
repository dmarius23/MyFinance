# Tenant-Configurable Message Templates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let each accounting firm edit the wording of the six client messages it sends (tax, report, payroll, and three document-reminder states) instead of that wording living in Java and TypeScript source.

**Architecture:** A `tenant_message_template` table holds only the kinds a tenant has actually customized; absent rows fall back to built-in Java defaults, so reset-to-default is free and defaults stay centrally improvable. A tiny `{{key}}` renderer in `ro.myfinance.common.messaging` substitutes values supplied by each module; computed lists (payment lines, missing transactions) arrive as pre-rendered **block** strings so the system keeps control of IBANs, money and row formats. Document-reminder rendering moves from the browser to the server, deleting `frontend/src/lib/reminderBody.ts`.

**Tech Stack:** Java 21, Spring Boot 3, Spring Data JPA, Flyway, PostgreSQL RLS, JUnit 5 + AssertJ + Testcontainers; React 18, Vite 5, TypeScript, TanStack Query, react-i18next.

**Spec:** `docs/superpowers/specs/2026-10-31-tenant-message-templates-design.md` — read it before Task 1. Section numbers referenced below (§3, §6, §9…) are that document's.

## Global Constraints

- **Migration version is `V62`** — `backend/src/main/resources/db/migration/V62__tenant_message_template.sql`. Latest existing is `V61__invoice_period_mode.sql`. Never edit an applied migration.
- **RLS is the tenant boundary.** Every tenant table gets `tenant_id`, `ENABLE` **and `FORCE` ROW LEVEL SECURITY`, and a fail-closed `tenant_isolation` policy on `app.tenant_id`. **A cross-tenant isolation test is mandatory** for every new data-access path (`backend/CLAUDE.md`).
- **`GRANT` to `myfinance_app` must be wrapped in the `pg_roles` existence check**, exactly as `V57__tenant_messaging_providers.sql` does it.
- **No new dependencies.** The renderer is hand-written; do not add Thymeleaf, Freemarker, Mustache or Handlebars.
- **Money and date formatting stay in Java**, never in a template. A tenant edits prose only.
- **`{{signOff}}` renders the sender + firm *name* lines only** — not the closing line. The closing ("O zi bună,", "Mulțumim,") is plain prose inside the template so the firm owns it.
- **`{{month}}` is `RomanianMonths.monthYear(...)`** → `"Aprilie 2026"`, for every kind.
- **Settings endpoints are `@PreAuthorize("hasRole('TENANT_ADMIN')")`**, matching `MessagingSettingsController`.
- **No PII in logs.** Never log a rendered body, a recipient address, or a partner name.
- **Default text must not change.** For `TAX`, `REPORT`, `PAYROLL` the rendered default is byte-identical to today's builder output; Task 3 pins this before any refactor.
- **Build commands:** backend `mvn -q test` (unit), `mvn -q verify` (adds `*IT` via failsafe, needs Docker); frontend `npm run build` and `npx tsc --noEmit` in `frontend/`. There is no `mvnw` — use system `mvn`.

## Review Focus

Five failure modes the spec implies but whose tests are easy to omit. Each has a test assigned to the task that owns the code.

1. **Data containing `{{…}}`** — a company legal name or partner name like `Firma {{test}} SRL` must be inserted literally, never re-substituted. A second rendering pass would let client data reach the template engine. → Task 2, Step 9.
2. **CRLF and trailing whitespace in a saved template** — a firm admin pastes from Word and every rendered line gains a stray `\r`. Normalize to `\n` on save *and* on render. → Task 2, Step 11 (render) and Task 10, Step 7 (save).
3. **A tenant deletes a block placeholder** — e.g. removes `{{missingTransactions}}` from `REMINDER_DOCS_MISSING`. Saving stays allowed (it is their prose), rendering must not throw, and the body simply omits the list. → Task 2, Step 13.
4. **A stale placeholder from an older contract** — a row saved before a kind's placeholder set changed. Renderer rule 3 strips it; it must never reach a client as literal `{{oldName}}`. → Task 2, Step 7.
5. **A very large block** — 200 missing transactions. Rendering must complete and stay correct; no silent truncation. → Task 9, Step 11.

---

## File Structure

**Backend — created**

| File | Responsibility |
|---|---|
| `resources/db/migration/V62__tenant_message_template.sql` | Table + RLS + grants |
| `common/messaging/MessageTemplateKind.java` | The six kinds; each declares its placeholder + block names |
| `common/messaging/TemplateRenderer.java` | `{{key}}` substitution, one pass, empty-line elision |
| `common/messaging/DefaultMessageTemplates.java` | Built-in subject + body per kind (§6) |
| `common/messaging/MessageTemplate.java` | JPA entity for `tenant_message_template` |
| `common/messaging/MessageTemplateRepository.java` | Spring Data repository |
| `common/messaging/MessageTemplateService.java` | `effective`/`render`/`save`/`reset`, tenant-scoped |
| `common/messaging/RenderedMessage.java` | `record RenderedMessage(String subject, String body)` |
| `settings/adapter/web/TemplateSettingsController.java` | TENANT_ADMIN CRUD + preview |
| `settings/adapter/web/TemplateSettingsDtos.java` | Request/response records |
| `settings/application/TemplateValidator.java` | Save-time validation rules (§8) |
| `extraction/application/ReminderBodyService.java` | Reminder state selection + render (§9) |

**Backend — modified**

| File | Change |
|---|---|
| `common/email/SignOff.java` | Add `names(sender, firm)`; keep `block(...)` for the invite |
| `taxpayments/application/PaymentEmailBuilder.java` | Prose → `DefaultMessageTemplates`; keeps block rendering |
| `taxpayments/application/TaxPaymentService.java:~300` | `compute(...)` renders via `MessageTemplateService` |
| `taxpayments/application/TaxEmailService.java:82` | Subject from the rendered template |
| `reports/application/ReportEmailBuilder.java` | Becomes a variable-map assembler |
| `reports/application/ReportEmailService.java:55` | `composeBody` returns subject + body |
| `payroll/application/PayrollEmailBuilder.java` | Becomes a variable-map assembler |
| `payroll/application/PayrollService.java:189` | `composeBody` returns subject + body |
| `extraction/application/DocumentReminderService.java:105` | Subject from template; delegate state to `ReminderBodyService` |
| `extraction/adapter/web/DocumentReminderController.java` | Add `GET …/document-reminders/body` |

**Frontend — created:** `src/api/templates.ts`, `src/components/MessageTemplatesSection.tsx`
**Frontend — modified:** `src/pages/DataSources.tsx:183`, `src/components/SendReminderModal.tsx`, `src/pages/Statements.tsx:132`, `src/pages/ReconcileWorkspace.tsx:296`, `src/api/documents.ts`, `src/i18n.ts`
**Frontend — deleted:** `src/lib/reminderBody.ts`

---

## Task 1: Migration, entity, repository, RLS isolation

**Files:**
- Create: `backend/src/main/resources/db/migration/V62__tenant_message_template.sql`
- Create: `backend/src/main/java/ro/myfinance/common/messaging/MessageTemplate.java`
- Create: `backend/src/main/java/ro/myfinance/common/messaging/MessageTemplateRepository.java`
- Test: `backend/src/test/java/ro/myfinance/messaging/MessageTemplateIsolationTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `MessageTemplate` entity with `getTenantId()`, `getKind()`, `getSubject()`, `setSubject(String)`, `getBody()`, `setBody(String)`, `setUpdatedBy(UUID)`; `MessageTemplateRepository extends JpaRepository<MessageTemplate, MessageTemplateId>` with `Optional<MessageTemplate> findByTenantIdAndKind(UUID, MessageTemplateKind)` and `List<MessageTemplate> findByTenantId(UUID)`.

> `MessageTemplateKind` is created in Task 2. To keep this task compilable on its own, declare the enum **first, minimally** in Step 3 below with just the six constants; Task 2 adds the placeholder sets to the same file.

- [ ] **Step 1: Write the failing isolation test**

Copy the harness from `backend/src/test/java/ro/myfinance/reports/PeriodReportIsolationTest.java` — same container image (`pgvector/pgvector:pg16`), same `myfinance_app` role creation, same Flyway invocation, same `assumeTrue(DockerClientFactory...)` skip. Create `backend/src/test/java/ro/myfinance/messaging/MessageTemplateIsolationTest.java` with that scaffolding plus:

```java
private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";

private static void seedTemplate(String tenantId, String kind, String body) throws Exception {
    try (Connection c = adminDataSource().getConnection();
         PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO tenant_message_template (tenant_id, kind, subject, body) VALUES (?::uuid, ?, ?, ?)")) {
        ps.setString(1, tenantId);
        ps.setString(2, kind);
        ps.setString(3, "Subiect " + tenantId.charAt(0));
        ps.setString(4, body);
        ps.executeUpdate();
    }
}

/** Bind the RLS tenant the way RlsDataSource does, then run the query as myfinance_app. */
private static <T> T asTenant(String tenantId, java.util.function.Function<Connection, T> work) throws Exception {
    try (Connection c = appDataSource.getConnection()) {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
            ps.setString(1, tenantId);
            ps.execute();
        }
        return work.apply(c);
    }
}

@Test
void tenantBCannotReadTenantATemplates() throws Exception {
    int visible = asTenant(TENANT_B, c -> count(c, "SELECT count(*) FROM tenant_message_template"));
    assertThat(visible).isEqualTo(1); // only B's own row, never A's
    String body = asTenant(TENANT_B, c -> single(c,
            "SELECT body FROM tenant_message_template WHERE kind = 'TAX'"));
    assertThat(body).isEqualTo("corp B");
}

@Test
void tenantBCannotUpdateTenantATemplates() throws Exception {
    int updated = asTenant(TENANT_B, c -> exec(c,
            "UPDATE tenant_message_template SET body = 'hijacked' WHERE tenant_id = '" + TENANT_A + "'"));
    assertThat(updated).isZero();
    String aBody = asTenant(TENANT_A, c -> single(c,
            "SELECT body FROM tenant_message_template WHERE kind = 'TAX'"));
    assertThat(aBody).isEqualTo("corp A");
}

@Test
void tenantBCannotDeleteTenantATemplates() throws Exception {
    int deleted = asTenant(TENANT_B, c -> exec(c,
            "DELETE FROM tenant_message_template WHERE tenant_id = '" + TENANT_A + "'"));
    assertThat(deleted).isZero();
}

@Test
void tenantBCannotInsertForTenantA() throws Exception {
    assertThatThrownBy(() -> asTenant(TENANT_B, c -> exec(c,
            "INSERT INTO tenant_message_template (tenant_id, kind, body) VALUES ('"
                    + TENANT_A + "', 'REPORT', 'forged')")))
            .hasMessageContaining("row-level security");
}
```

Add the `count`/`single`/`exec` helpers (wrap `SQLException` in `RuntimeException`), and in `@BeforeAll` after `migrate()`: `seedTenant(TENANT_A, "Tenant A SRL"); seedTenant(TENANT_B, "Tenant B SRL"); seedTemplate(TENANT_A, "TAX", "corp A"); seedTemplate(TENANT_B, "TAX", "corp B");`

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd backend && mvn -q -Dtest=MessageTemplateIsolationTest test`
Expected: FAIL — `relation "tenant_message_template" does not exist`.

- [ ] **Step 3: Add the minimal enum**

Create `backend/src/main/java/ro/myfinance/common/messaging/MessageTemplateKind.java`:

```java
package ro.myfinance.common.messaging;

/** The client-facing messages a tenant may reword. Placeholder contracts are added in Task 2. */
public enum MessageTemplateKind {
    TAX,
    REPORT,
    PAYROLL,
    REMINDER_NOTHING_RECEIVED,
    REMINDER_DOCS_MISSING,
    REMINDER_DOCS_ONLY
}
```

- [ ] **Step 4: Write the migration**

Create `backend/src/main/resources/db/migration/V62__tenant_message_template.sql`:

```sql
-- Per-tenant message wording. A row exists ONLY for a kind the tenant customized; absent rows fall back
-- to the built-in Java defaults (DefaultMessageTemplates), so defaults stay centrally improvable and
-- "reset to default" is just a DELETE. Bodies are prose with {{placeholders}}; the computed lists
-- (payment lines, missing transactions) are rendered by the app, never by the tenant.

CREATE TABLE tenant_message_template (
    tenant_id  uuid NOT NULL REFERENCES tenant(id),
    kind       text NOT NULL,
    subject    text,
    body       text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid,
    PRIMARY KEY (tenant_id, kind)
);
ALTER TABLE tenant_message_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_message_template FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_message_template
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_message_template TO myfinance_app;
    END IF;
END $$;
```

- [ ] **Step 5: Run the isolation test to verify it passes**

Run: `cd backend && mvn -q -Dtest=MessageTemplateIsolationTest test`
Expected: PASS (4 tests), or skipped if Docker is unavailable — if skipped, start Docker and re-run; this test is the golden-rule gate and must actually execute once.

- [ ] **Step 6: Add the entity and repository**

Create `backend/src/main/java/ro/myfinance/common/messaging/MessageTemplate.java`:

```java
package ro.myfinance.common.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One tenant's wording for one message kind. Present only when customized — {@link MessageTemplateService}
 * falls back to {@link DefaultMessageTemplates} otherwise. RLS enforces tenant isolation.
 */
@Entity
@Table(name = "tenant_message_template")
@IdClass(MessageTemplate.Key.class)
public class MessageTemplate {

    /** Composite PK (tenant_id, kind). */
    public static class Key implements Serializable {
        private UUID tenantId;
        private MessageTemplateKind kind;

        public Key() {
        }

        public Key(UUID tenantId, MessageTemplateKind kind) {
            this.tenantId = tenantId;
            this.kind = kind;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key k)) {
                return false;
            }
            return Objects.equals(tenantId, k.tenantId) && kind == k.kind;
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, kind);
        }
    }

    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private MessageTemplateKind kind;

    /** Null means "use the built-in default subject" — a tenant may reword the body only. */
    @Column(name = "subject")
    private String subject;

    @Column(name = "body", nullable = false)
    private String body;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected MessageTemplate() {
    }

    public MessageTemplate(UUID tenantId, MessageTemplateKind kind) {
        this.tenantId = tenantId;
        this.kind = kind;
    }

    public UUID getTenantId() { return tenantId; }
    public MessageTemplateKind getKind() { return kind; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
```

Create `backend/src/main/java/ro/myfinance/common/messaging/MessageTemplateRepository.java`:

```java
package ro.myfinance.common.messaging;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, MessageTemplate.Key> {

    Optional<MessageTemplate> findByTenantIdAndKind(UUID tenantId, MessageTemplateKind kind);

    List<MessageTemplate> findByTenantId(UUID tenantId);

    void deleteByTenantIdAndKind(UUID tenantId, MessageTemplateKind kind);
}
```

- [ ] **Step 7: Verify the whole suite still compiles and passes**

Run: `cd backend && mvn -q test`
Expected: PASS, no new failures.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V62__tenant_message_template.sql \
        backend/src/main/java/ro/myfinance/common/messaging/ \
        backend/src/test/java/ro/myfinance/messaging/MessageTemplateIsolationTest.java
git commit -m "feat(messaging): add tenant_message_template table with RLS

Per-tenant message wording, stored only when customized. Cross-tenant
isolation test proves tenant B cannot read, update, delete or forge
tenant A's templates as the non-superuser myfinance_app role."
```

---

## Task 2: Placeholder contracts and the renderer

**Files:**
- Modify: `backend/src/main/java/ro/myfinance/common/messaging/MessageTemplateKind.java`
- Create: `backend/src/main/java/ro/myfinance/common/messaging/TemplateRenderer.java`
- Test: `backend/src/test/java/ro/myfinance/messaging/TemplateRendererTest.java`
- Test: `backend/src/test/java/ro/myfinance/messaging/MessageTemplateKindTest.java`

**Interfaces:**
- Consumes: `MessageTemplateKind` (six constants) from Task 1.
- Produces:
  - `MessageTemplateKind.placeholders() -> Set<String>`, `.blocks() -> Set<String>`, `.allNames() -> Set<String>` (placeholders + blocks + `"signOff"`).
  - `TemplateRenderer` as a Spring `@Component` with `String render(String template, Map<String, String> values)`.

- [ ] **Step 1: Write the failing contract test**

Create `backend/src/test/java/ro/myfinance/messaging/MessageTemplateKindTest.java`:

```java
package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ro.myfinance.common.messaging.MessageTemplateKind;

class MessageTemplateKindTest {

    @Test
    void taxDeclaresItsPlaceholdersAndTwoBlocks() {
        assertThat(MessageTemplateKind.TAX.placeholders())
                .containsExactlyInAnyOrder("companyName", "cui", "month", "treasury", "totalToPay");
        assertThat(MessageTemplateKind.TAX.blocks())
                .containsExactlyInAnyOrder("paymentSummary", "paymentAccounts");
    }

    @Test
    void reportDeclaresItsFigures() {
        assertThat(MessageTemplateKind.REPORT.placeholders())
                .containsExactlyInAnyOrder("companyName", "cui", "month", "revenue", "expenses",
                        "grossProfit", "incomeTax", "netProfit");
        assertThat(MessageTemplateKind.REPORT.blocks()).isEmpty();
    }

    @Test
    void payrollDeclaresTheDeadline() {
        assertThat(MessageTemplateKind.PAYROLL.placeholders())
                .containsExactlyInAnyOrder("companyName", "month", "taxDeadline");
        assertThat(MessageTemplateKind.PAYROLL.blocks()).isEmpty();
    }

    @Test
    void onlyTheDocsMissingReminderCarriesTheTransactionBlock() {
        assertThat(MessageTemplateKind.REMINDER_NOTHING_RECEIVED.blocks()).isEmpty();
        assertThat(MessageTemplateKind.REMINDER_DOCS_ONLY.blocks()).isEmpty();
        assertThat(MessageTemplateKind.REMINDER_DOCS_MISSING.blocks())
                .containsExactly("missingTransactions");
        assertThat(MessageTemplateKind.REMINDER_DOCS_MISSING.placeholders())
                .containsExactlyInAnyOrder("companyName", "month", "missingCount");
    }

    @Test
    void everyKindAllowsSignOff() {
        for (MessageTemplateKind k : MessageTemplateKind.values()) {
            assertThat(k.allNames()).contains("signOff");
        }
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd backend && mvn -q -Dtest=MessageTemplateKindTest test`
Expected: FAIL — `cannot find symbol: method placeholders()`.

- [ ] **Step 3: Fill in the enum contracts**

Replace `backend/src/main/java/ro/myfinance/common/messaging/MessageTemplateKind.java` with:

```java
package ro.myfinance.common.messaging;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The client-facing messages a tenant may reword, each declaring the placeholder names its template may
 * use. {@code placeholders} are scalars (a company name, a month, a figure); {@code blocks} are
 * multi-line lists the application renders — a tenant positions a block but never formats its rows.
 *
 * <p>{@code signOff} is allowed everywhere: it renders the sender and firm name lines, so a firm can
 * never hardcode the wrong firm name. The closing line above it ("O zi bună,") is ordinary prose the
 * tenant owns.
 *
 * <p>Saves are validated against {@link #allNames()}, so a typo like {@code {{compnayName}}} fails in
 * Settings instead of reaching a client as literal text.
 */
public enum MessageTemplateKind {

    TAX(Set.of("companyName", "cui", "month", "treasury", "totalToPay"),
            Set.of("paymentSummary", "paymentAccounts")),

    REPORT(Set.of("companyName", "cui", "month", "revenue", "expenses", "grossProfit", "incomeTax",
            "netProfit"), Set.of()),

    PAYROLL(Set.of("companyName", "month", "taxDeadline"), Set.of()),

    REMINDER_NOTHING_RECEIVED(Set.of("companyName", "month"), Set.of()),

    REMINDER_DOCS_MISSING(Set.of("companyName", "month", "missingCount"),
            Set.of("missingTransactions")),

    REMINDER_DOCS_ONLY(Set.of("companyName", "month"), Set.of());

    /** Available to every kind — the sender + firm name lines. */
    public static final String SIGN_OFF = "signOff";

    private final Set<String> placeholders;
    private final Set<String> blocks;

    MessageTemplateKind(Set<String> placeholders, Set<String> blocks) {
        this.placeholders = Set.copyOf(placeholders);
        this.blocks = Set.copyOf(blocks);
    }

    public Set<String> placeholders() {
        return placeholders;
    }

    public Set<String> blocks() {
        return blocks;
    }

    /** Every name a template of this kind may reference, including {@link #SIGN_OFF}. */
    public Set<String> allNames() {
        Set<String> all = new LinkedHashSet<>(placeholders);
        all.addAll(blocks);
        all.add(SIGN_OFF);
        return Set.copyOf(all);
    }

    /** True when this kind is one of the three document-reminder states. */
    public boolean isReminder() {
        return this == REMINDER_NOTHING_RECEIVED || this == REMINDER_DOCS_MISSING
                || this == REMINDER_DOCS_ONLY;
    }
}
```

- [ ] **Step 4: Run the contract test to verify it passes**

Run: `cd backend && mvn -q -Dtest=MessageTemplateKindTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Write the failing renderer test — substitution basics**

Create `backend/src/test/java/ro/myfinance/messaging/TemplateRendererTest.java`:

```java
package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.messaging.TemplateRenderer;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void substitutesKnownPlaceholders() {
        String out = renderer.render("Bună ziua, {{companyName}} ({{cui}})",
                Map.of("companyName", "Acme SRL", "cui", "RO123"));
        assertThat(out).isEqualTo("Bună ziua, Acme SRL (RO123)");
    }

    @Test
    void toleratesWhitespaceInsideDelimiters() {
        String out = renderer.render("luna {{ month }}", Map.of("month", "Aprilie 2026"));
        assertThat(out).isEqualTo("luna Aprilie 2026");
    }

    @Test
    void insertsMultiLineBlocksVerbatim() {
        String out = renderer.render("Conturi:\n{{paymentAccounts}}\nGata",
                Map.of("paymentAccounts", "- 100 lei\n- Beneficiar: Trezoreria Cluj"));
        assertThat(out).isEqualTo("Conturi:\n- 100 lei\n- Beneficiar: Trezoreria Cluj\nGata");
    }
}
```

- [ ] **Step 6: Run to confirm it fails**

Run: `cd backend && mvn -q -Dtest=TemplateRendererTest test`
Expected: FAIL — `cannot find symbol: class TemplateRenderer`.

- [ ] **Step 7: Add the remaining renderer tests (elision, unknowns, blanks)**

Append to `TemplateRendererTest`:

```java
    @Test
    void blankValueRendersEmptyWithinALineThatHasOtherText() {
        String out = renderer.render("Total: {{totalToPay}} lei", Map.of("totalToPay", ""));
        assertThat(out).isEqualTo("Total:  lei");
    }

    /** Review Focus 4: a stale name from an older contract must never reach a client literally. */
    @Test
    void unknownPlaceholderIsStrippedNotEchoed() {
        String out = renderer.render("Salut {{removedInV7}}!", Map.of());
        assertThat(out).isEqualTo("Salut !");
        assertThat(out).doesNotContain("{{");
    }

    @Test
    void dropsALineWhoseOnlyContentIsAnEmptyPlaceholder() {
        String out = renderer.render("O zi bună,\n{{signOff}}", Map.of("signOff", ""));
        assertThat(out).isEqualTo("O zi bună,");
    }

    @Test
    void keepsALineWhosePlaceholderHasAValue() {
        String out = renderer.render("O zi bună,\n{{signOff}}",
                Map.of("signOff", "Maria Popescu\nContaZone SRL"));
        assertThat(out).isEqualTo("O zi bună,\nMaria Popescu\nContaZone SRL");
    }

    @Test
    void keepsDeliberateBlankLinesThatContainNoPlaceholder() {
        String out = renderer.render("Prima\n\nA doua", Map.of());
        assertThat(out).isEqualTo("Prima\n\nA doua");
    }

    @Test
    void nullTemplateRendersEmpty() {
        assertThat(renderer.render(null, Map.of())).isEmpty();
    }
```

- [ ] **Step 8: Run to confirm the new tests fail for the right reason**

Run: `cd backend && mvn -q -Dtest=TemplateRendererTest test`
Expected: FAIL — still `cannot find symbol`, since the class does not exist yet.

- [ ] **Step 9: Add the Review Focus 1 test — client data containing placeholder syntax**

Append to `TemplateRendererTest`:

```java
    /**
     * Review Focus 1: a company or partner name may itself contain {{…}}. Substitution is single-pass,
     * so injected data is never re-scanned — otherwise client data would reach the template engine.
     */
    @Test
    void valuesContainingPlaceholderSyntaxAreInsertedLiterally() {
        String out = renderer.render("Firma {{companyName}} datorează {{total}}",
                Map.of("companyName", "Acme {{total}} SRL", "total", "500"));
        assertThat(out).isEqualTo("Firma Acme {{total}} SRL datorează 500");
    }

    @Test
    void unbalancedDelimitersInATemplateAreLeftAlone() {
        String out = renderer.render("Suma {{ este mare", Map.of("month", "Aprilie 2026"));
        assertThat(out).isEqualTo("Suma {{ este mare");
    }
```

- [ ] **Step 10: Implement the renderer**

Create `backend/src/main/java/ro/myfinance/common/messaging/TemplateRenderer.java`:

```java
package ro.myfinance.common.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Substitutes {@code {{name}}} placeholders in a tenant's message template.
 *
 * <p>Deliberately not a template language: no conditionals, no loops, no expressions. Computed lists
 * arrive already rendered as multi-line block strings, so there is nothing for a tenant to get wrong
 * beyond their own prose.
 *
 * <p>Three rules matter:
 * <ol>
 *   <li><b>Single pass.</b> Substituted values are never re-scanned, so a company name containing
 *       {@code {{…}}} is inserted literally rather than treated as a placeholder.</li>
 *   <li><b>Empty-line elision.</b> A line whose literal text is blank and whose every placeholder
 *       resolved to empty is dropped. This keeps {@code "O zi bună,\n{{signOff}}"} correct when both
 *       sender and firm are unknown, and stops an empty block leaving a stray blank line.</li>
 *   <li><b>Unknown names are stripped.</b> Saves are validated against
 *       {@link MessageTemplateKind#allNames()}, so this only catches a row written before a kind's
 *       contract changed — defence in depth, so no client ever sees literal {@code {{oldName}}}.</li>
 * </ol>
 *
 * <p>Never throws on content. Malformed delimiters are rejected at save time, and anything that slips
 * through is left as-is rather than failing a send.
 */
@Component
public class TemplateRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");

    public String render(String template, Map<String, String> values) {
        if (template == null) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (String line : normalize(template).split("\n", -1)) {
            Line rendered = renderLine(line, values);
            if (rendered.elide()) {
                continue;
            }
            out.add(rendered.text());
        }
        return String.join("\n", out);
    }

    /** Collapse CRLF/CR so a template pasted from Word cannot put stray \r into a client's email. */
    public static String normalize(String text) {
        return text == null ? null : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private record Line(String text, boolean elide) {
    }

    private Line renderLine(String line, Map<String, String> values) {
        Matcher m = TOKEN.matcher(line);
        StringBuilder rendered = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        boolean sawToken = false;
        boolean allTokensEmpty = true;
        int cursor = 0;
        while (m.find()) {
            sawToken = true;
            rendered.append(line, cursor, m.start());
            literal.append(line, cursor, m.start());
            String value = values.get(m.group(1));
            if (value != null && !value.isEmpty()) {
                allTokensEmpty = false;
                rendered.append(value);
            }
            cursor = m.end();
        }
        rendered.append(line.substring(cursor));
        literal.append(line.substring(cursor));
        boolean elide = sawToken && allTokensEmpty && literal.toString().isBlank();
        return new Line(rendered.toString(), elide);
    }
}
```

- [ ] **Step 11: Add the CRLF test (Review Focus 2, render half)**

Append to `TemplateRendererTest`:

```java
    /** Review Focus 2: a template pasted from Word must not put \r into a client's email. */
    @Test
    void normalizesWindowsAndClassicMacLineEndings() {
        assertThat(renderer.render("Prima\r\nA doua\rA treia", Map.of()))
                .isEqualTo("Prima\nA doua\nA treia")
                .doesNotContain("\r");
    }
```

- [ ] **Step 12: Run the full renderer suite**

Run: `cd backend && mvn -q -Dtest=TemplateRendererTest test`
Expected: PASS (13 tests).

- [ ] **Step 13: Add the omitted-block test (Review Focus 3)**

Append to `TemplateRendererTest`:

```java
    /**
     * Review Focus 3: a tenant may delete a block placeholder. That is their prose decision — rendering
     * must not throw and must not reinstate the list; the message simply goes out without it.
     */
    @Test
    void templateOmittingItsBlockStillRenders() {
        String out = renderer.render("Bună ziua,\n\nTrimiteți documentele pentru {{month}}.",
                Map.of("month", "Aprilie 2026", "missingTransactions", "• 15.04.2026 — Acme — 100 RON"));
        assertThat(out).isEqualTo("Bună ziua,\n\nTrimiteți documentele pentru Aprilie 2026.");
        assertThat(out).doesNotContain("15.04.2026");
    }
```

- [ ] **Step 14: Run to verify it passes**

Run: `cd backend && mvn -q -Dtest=TemplateRendererTest,MessageTemplateKindTest test`
Expected: PASS (14 + 5 tests).

- [ ] **Step 15: Commit**

```bash
git add backend/src/main/java/ro/myfinance/common/messaging/MessageTemplateKind.java \
        backend/src/main/java/ro/myfinance/common/messaging/TemplateRenderer.java \
        backend/src/test/java/ro/myfinance/messaging/TemplateRendererTest.java \
        backend/src/test/java/ro/myfinance/messaging/MessageTemplateKindTest.java
git commit -m "feat(messaging): add placeholder contracts and template renderer

Single-pass {{name}} substitution with empty-line elision, CRLF
normalization and unknown-name stripping. Single-pass matters: client
data containing {{...}} is inserted literally, never re-scanned."
```

---

## Task 3: Pin today's output (characterization tests)

This task changes **no production code**. It captures the current wording as executable expectations so the next five tasks can refactor underneath it. If a test here fails, the refactor broke a client-visible message.

**Files:**
- Test: `backend/src/test/java/ro/myfinance/messaging/CurrentMessageWordingTest.java`

**Interfaces:**
- Consumes: existing `PaymentEmailBuilder.build(...)`, `ReportEmailBuilder.body/subject(...)`, `PayrollEmailBuilder.body/subject(...)`.
- Produces: the three `EXPECTED_*` string constants that Task 4 reuses as its golden targets.

Formatting facts the expectations rely on (verified in source, do not re-derive):
- `RomanianMonths.monthYear(...)` → `"Aprilie 2026"`.
- `PaymentCalculator.monthYear(YearMonth)` → dash form `"Aprilie-2026"`; subjects and bodies call `.replace('-', ' ')`. The dash form survives **inside** `explanation` (e.g. `"TVA Aprilie-2026"`), which is block content and stays system-owned.
- `ReportPdfGenerator.money(...)` → `DecimalFormat("#,##0.00")` with **space** grouping and **dot** decimal: `60000` → `"60 000.00"`.
- `PaymentEmailBuilder.plain(line)` → `amount.stripTrailingZeros().toPlainString()`: `1250.00` → `"1250"`.

- [ ] **Step 1: Write the payroll characterization test**

Create `backend/src/test/java/ro/myfinance/messaging/CurrentMessageWordingTest.java`:

```java
package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.payroll.application.PayrollEmailBuilder;

/**
 * Pins the exact wording clients receive today, before templates are introduced. These assertions are
 * the regression net for Tasks 4–8: the rendered built-in default must reproduce them byte for byte, so
 * a firm that never customizes sees no change whatsoever.
 */
class CurrentMessageWordingTest {

    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);
    private static final String SENDER = "Maria Popescu";
    private static final String FIRM = "ContaZone SRL";

    static final String EXPECTED_PAYROLL_BODY = """
            Bună ziua,

            Vă rog frumos să regăsiți atașate statul de plată, fluturașul de salariu și pontajul pentru luna Aprilie 2026.

            Restul de plată de pe statul de plată și fluturaș trebuie virat din contul firmei în contul personal al salariatului.

            Diferența dintre restul de plată și salariul brut reprezintă taxele salariale. Acestea se vor plăti până în data de 25 Aprilie 2026.

            În ceea ce privește sumele care se plătesc către Trezorerie, precum și conturile aferente, vom reveni cu un email separat.

            O zi bună,
            Maria Popescu
            ContaZone SRL""";

    @Test
    void payrollBodyIsUnchanged() {
        assertThat(PayrollEmailBuilder.body(APRIL, SENDER, FIRM)).isEqualTo(EXPECTED_PAYROLL_BODY);
    }

    @Test
    void payrollSubjectIsUnchanged() {
        assertThat(PayrollEmailBuilder.subject(APRIL)).isEqualTo("State de plată — Aprilie 2026");
    }

    @Test
    void payrollSignsWithFirmOnlyWhenSenderUnknown() {
        assertThat(PayrollEmailBuilder.body(APRIL, null, FIRM))
                .endsWith("O zi bună,\nContaZone SRL");
    }
}
```

Note the Java text block has no trailing newline (the closing `"""` sits immediately after `ContaZone SRL`), matching `String.join("\n", …)`.

- [ ] **Step 2: Run it — it must pass against unmodified code**

Run: `cd backend && mvn -q -Dtest=CurrentMessageWordingTest test`
Expected: PASS (3 tests). A failure here means the expectation is wrong, not the code — fix the expectation by pasting the actual output from the assertion diff.

- [ ] **Step 3: Add the report characterization test**

Append to `CurrentMessageWordingTest`:

```java
    private static ro.myfinance.reports.domain.ReportData reportData() {
        var pl = new ro.myfinance.reports.domain.ReportData.ProfitLoss(
                new java.math.BigDecimal("100000"), java.util.List.of(),
                new java.math.BigDecimal("60000"), java.util.List.of(),
                new java.math.BigDecimal("40000"),
                new java.math.BigDecimal("1000"),
                new java.math.BigDecimal("39000"));
        var bs = new ro.myfinance.reports.domain.ReportData.BalanceSheet(
                java.util.List.of(), java.math.BigDecimal.ZERO,
                java.util.List.of(), java.math.BigDecimal.ZERO,
                java.util.List.of(), java.math.BigDecimal.ZERO);
        var kpis = new ro.myfinance.reports.domain.ReportData.Kpis(
                null, null, null, null, null, null);
        return new ro.myfinance.reports.domain.ReportData("Acme SRL", "RO123456",
                APRIL, APRIL.withDayOfMonth(30), true, pl, bs, kpis);
    }

    static final String EXPECTED_REPORT_BODY = """
            Bună ziua,

            Vă transmitem atașat raportul financiar pentru luna Aprilie 2026.

            Pe scurt:
            • Venituri: 100 000.00 RON
            • Cheltuieli: 61 000.00 RON
            • Profit net: 39 000.00 RON

            Detaliile complete și graficele se găsesc în documentele atașate.

            O zi bună,
            Maria Popescu
            ContaZone SRL""";

    @Test
    void reportBodyIsUnchanged() {
        assertThat(ro.myfinance.reports.application.ReportEmailBuilder
                .body(APRIL, reportData(), SENDER, FIRM))
                .isEqualTo(EXPECTED_REPORT_BODY);
    }

    @Test
    void reportSubjectIsUnchanged() {
        assertThat(ro.myfinance.reports.application.ReportEmailBuilder.subject(APRIL))
                .isEqualTo("Raport financiar — Aprilie 2026");
    }
```

`Cheltuieli` is `operatingExpenses + incomeTax` = `60000 + 1000`, which is why it reads `61 000.00`.

- [ ] **Step 4: Run it**

Run: `cd backend && mvn -q -Dtest=CurrentMessageWordingTest test`
Expected: PASS (5 tests). If `ReportEmailBuilder` is package-private, add the test to package `ro.myfinance.reports` instead and import the shared constants; do **not** widen production visibility for a test.

- [ ] **Step 5: Add the tax characterization test**

Append to `CurrentMessageWordingTest`:

```java
    static final String EXPECTED_TAX_BODY = """
            Bună ziua,

            Sumele de plată pentru luna Aprilie 2026 aferente firmei Acme SRL sunt următoarele:

            TVA: 1250
            Impozite: 300
            Conturi:

            - 1250 lei in contul RO11TREZ1112345678901234
            - Beneficiar: Trezoreria Cluj Napoca
            - CUI Beneficiar: RO123456
            - Explicație: TVA Aprilie-2026
            - Scadență: 25 aprilie 2026

            - 300 lei in contul RO22TREZ2212345678901234
            - Beneficiar: Trezoreria Cluj Napoca
            - CUI Beneficiar: RO123456
            - Explicație: Impozite Aprilie-2026

            O zi plăcută,
            Maria Popescu
            ContaZone SRL
            """;

    @Test
    void taxBodyIsUnchanged() {
        var lines = java.util.List.of(
                new ro.myfinance.taxpayments.domain.PaymentLine(
                        "RO11TREZ1112345678901234", new java.math.BigDecimal("1250.00"),
                        java.util.List.of(ro.myfinance.taxpayments.domain.TaxCategory.TVA),
                        "TVA Aprilie-2026", LocalDate.of(2026, 4, 25)),
                new ro.myfinance.taxpayments.domain.PaymentLine(
                        "RO22TREZ2212345678901234", new java.math.BigDecimal("300"),
                        java.util.List.of(ro.myfinance.taxpayments.domain.TaxCategory.IMPOZIT_PROFIT),
                        "Impozite Aprilie-2026", null));
        String body = new ro.myfinance.taxpayments.application.PaymentEmailBuilder().build(
                "Acme SRL", "RO123456", java.time.YearMonth.of(2026, 4), "Trezoreria Cluj Napoca",
                lines, SENDER, FIRM);
        assertThat(body).isEqualTo(EXPECTED_TAX_BODY);
    }
```

Unlike the other two, the tax body **ends with a newline** (the builder appends one after the sign-off) — hence the text block's closing `"""` on its own line.

- [ ] **Step 6: Run it and reconcile whitespace**

Run: `cd backend && mvn -q -Dtest=CurrentMessageWordingTest test`
Expected: PASS (6 tests).

This expectation is the fiddliest in the plan: the `Conturi:` line is followed by a blank line because each account group begins with `\n`, and the two groups are separated by that same leading blank line. If the assertion diff disagrees, **trust the actual output** — paste it in verbatim. Also confirm `TaxCategory.IMPOZIT_PROFIT` exists (`grep -n 'IMPOZIT' backend/src/main/java/ro/myfinance/taxpayments/domain/TaxCategory.java`); if the constant is named differently, use any constant that makes `shortLabel` return `"Impozite"`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/test/java/ro/myfinance/messaging/CurrentMessageWordingTest.java
git commit -m "test(messaging): pin current tax, report and payroll wording

Characterization tests capturing the exact bodies and subjects clients
receive today. No production change. These are the regression net for
the template refactor that follows."
```

---

## Task 4: Built-in defaults and `SignOff.names`

**Files:**
- Create: `backend/src/main/java/ro/myfinance/common/messaging/DefaultMessageTemplates.java`
- Modify: `backend/src/main/java/ro/myfinance/common/email/SignOff.java`
- Test: `backend/src/test/java/ro/myfinance/messaging/DefaultMessageTemplatesTest.java`

**Interfaces:**
- Consumes: `MessageTemplateKind`, `TemplateRenderer` (Task 2); the `EXPECTED_*` constants (Task 3).
- Produces:
  - `DefaultMessageTemplates.Template` — `record Template(String subject, String body)`.
  - `DefaultMessageTemplates.of(MessageTemplateKind) -> Template`.
  - `SignOff.names(String senderName, String firmName) -> String` — non-blank parts joined by `\n`, `""` when both blank.

- [ ] **Step 1: Write the failing `SignOff.names` test**

Create `backend/src/test/java/ro/myfinance/messaging/SignOffNamesTest.java`:

```java
package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ro.myfinance.common.email.SignOff;

/**
 * {@code names} is the {{signOff}} block: the sender and firm name lines only. The closing line above it
 * is prose the tenant owns, which is why it is no longer part of this call.
 */
class SignOffNamesTest {

    @Test
    void joinsSenderAndFirm() {
        assertThat(SignOff.names("Maria Popescu", "ContaZone SRL"))
                .isEqualTo("Maria Popescu\nContaZone SRL");
    }

    @Test
    void dropsBlankSender() {
        assertThat(SignOff.names("  ", "ContaZone SRL")).isEqualTo("ContaZone SRL");
        assertThat(SignOff.names(null, "ContaZone SRL")).isEqualTo("ContaZone SRL");
    }

    @Test
    void dropsBlankFirm() {
        assertThat(SignOff.names("Maria Popescu", null)).isEqualTo("Maria Popescu");
    }

    /** Both blank yields empty, so the renderer elides the whole line rather than leaving a dangling \n. */
    @Test
    void bothBlankYieldsEmpty() {
        assertThat(SignOff.names(null, " ")).isEmpty();
    }

    @Test
    void trimsEachName() {
        assertThat(SignOff.names(" Maria ", " ContaZone ")).isEqualTo("Maria\nContaZone");
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd backend && mvn -q -Dtest=SignOffNamesTest test`
Expected: FAIL — `cannot find symbol: method names(String,String)`.

- [ ] **Step 3: Add `names` to `SignOff`**

In `backend/src/main/java/ro/myfinance/common/email/SignOff.java`, keep `block(...)` unchanged (the invite email still uses it) and add:

```java
    /**
     * The {@code {{signOff}}} block: sender then firm, one per line, blanks dropped. Returns {@code ""}
     * when neither is known, so {@link ro.myfinance.common.messaging.TemplateRenderer} elides the line
     * instead of leaving a trailing newline under the closing.
     *
     * <p>The closing line ("O zi bună,") is deliberately NOT included: it is prose the tenant owns in
     * their template. {@link #block(String, String, String)} remains for the hardcoded invite email.
     */
    public static String names(String senderName, String firmName) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, senderName);
        appendLine(sb, firmName);
        return sb.isEmpty() ? "" : sb.substring(1); // drop the leading '\n' appendLine adds
    }
```

`appendLine` already prepends `'\n'` and skips null/blank while trimming, so `substring(1)` yields exactly the joined names.

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && mvn -q -Dtest=SignOffNamesTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Write the failing golden-default test**

Create `backend/src/test/java/ro/myfinance/messaging/DefaultMessageTemplatesTest.java`:

```java
package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.email.SignOff;
import ro.myfinance.common.messaging.DefaultMessageTemplates;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.TemplateRenderer;

/**
 * The golden net: each built-in default, rendered with the same values the old builders used, must equal
 * the wording pinned in {@link CurrentMessageWordingTest}. A firm that never customizes must see no
 * change at all — this is the test that guarantees it.
 */
class DefaultMessageTemplatesTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    private String render(MessageTemplateKind kind, Map<String, String> values) {
        Map<String, String> all = new HashMap<>(values);
        all.put("signOff", SignOff.names("Maria Popescu", "ContaZone SRL"));
        return renderer.render(DefaultMessageTemplates.of(kind).body(), all);
    }

    @Test
    void payrollDefaultMatchesTodaysWording() {
        String body = render(MessageTemplateKind.PAYROLL, Map.of(
                "companyName", "Acme SRL",
                "month", "Aprilie 2026",
                "taxDeadline", "25 Aprilie 2026"));
        assertThat(body).isEqualTo(CurrentMessageWordingTest.EXPECTED_PAYROLL_BODY);
    }

    @Test
    void reportDefaultMatchesTodaysWording() {
        String body = render(MessageTemplateKind.REPORT, Map.of(
                "companyName", "Acme SRL",
                "cui", "RO123456",
                "month", "Aprilie 2026",
                "revenue", "100 000.00",
                "expenses", "61 000.00",
                "grossProfit", "40 000.00",
                "incomeTax", "1 000.00",
                "netProfit", "39 000.00"));
        assertThat(body).isEqualTo(CurrentMessageWordingTest.EXPECTED_REPORT_BODY);
    }

    @Test
    void taxDefaultMatchesTodaysWording() {
        String summary = "TVA: 1250\nImpozite: 300";
        String accounts = String.join("\n",
                "",
                "- 1250 lei in contul RO11TREZ1112345678901234",
                "- Beneficiar: Trezoreria Cluj Napoca",
                "- CUI Beneficiar: RO123456",
                "- Explicație: TVA Aprilie-2026",
                "- Scadență: 25 aprilie 2026",
                "",
                "- 300 lei in contul RO22TREZ2212345678901234",
                "- Beneficiar: Trezoreria Cluj Napoca",
                "- CUI Beneficiar: RO123456",
                "- Explicație: Impozite Aprilie-2026");
        String body = render(MessageTemplateKind.TAX, Map.of(
                "companyName", "Acme SRL",
                "cui", "RO123456",
                "month", "Aprilie 2026",
                "treasury", "Trezoreria Cluj Napoca",
                "totalToPay", "1550",
                "paymentSummary", summary,
                "paymentAccounts", accounts));
        assertThat(body).isEqualTo(CurrentMessageWordingTest.EXPECTED_TAX_BODY);
    }

    @Test
    void everyKindHasANonBlankDefaultSubjectAndBody() {
        for (MessageTemplateKind kind : MessageTemplateKind.values()) {
            var t = DefaultMessageTemplates.of(kind);
            assertThat(t.subject()).as("subject of %s", kind).isNotBlank();
            assertThat(t.body()).as("body of %s", kind).isNotBlank();
        }
    }

    @Test
    void everyDefaultUsesOnlyNamesItsKindDeclares() {
        var token = java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");
        for (MessageTemplateKind kind : MessageTemplateKind.values()) {
            var t = DefaultMessageTemplates.of(kind);
            var m = token.matcher(t.body() + "\n" + t.subject());
            while (m.find()) {
                assertThat(kind.allNames()).as("%s uses {{%s}}", kind, m.group(1)).contains(m.group(1));
            }
        }
    }

    @Test
    void reminderDefaultsAskForTheRightThing() {
        assertThat(DefaultMessageTemplates.of(MessageTemplateKind.REMINDER_NOTHING_RECEIVED).body())
                .contains("extrasul de cont");
        assertThat(DefaultMessageTemplates.of(MessageTemplateKind.REMINDER_DOCS_ONLY).body())
                .doesNotContain("extrasul de cont")
                .contains("facturile / chitanțele");
        assertThat(DefaultMessageTemplates.of(MessageTemplateKind.REMINDER_DOCS_MISSING).body())
                .contains("{{missingTransactions}}");
    }

    @Test
    void allThreeReminderSubjectsAreTheSame() {
        String expected = "Documente lipsă — {{month}}";
        assertThat(DefaultMessageTemplates.of(MessageTemplateKind.REMINDER_NOTHING_RECEIVED).subject())
                .isEqualTo(expected);
        assertThat(DefaultMessageTemplates.of(MessageTemplateKind.REMINDER_DOCS_MISSING).subject())
                .isEqualTo(expected);
        assertThat(DefaultMessageTemplates.of(MessageTemplateKind.REMINDER_DOCS_ONLY).subject())
                .isEqualTo(expected);
    }
}
```

Make the three `EXPECTED_*` constants in `CurrentMessageWordingTest` package-visible `static final` (they already are, if written as in Task 3) and ensure both tests sit in package `ro.myfinance.messaging`.

- [ ] **Step 6: Run to confirm it fails**

Run: `cd backend && mvn -q -Dtest=DefaultMessageTemplatesTest test`
Expected: FAIL — `cannot find symbol: class DefaultMessageTemplates`.

- [ ] **Step 7: Write the defaults**

Create `backend/src/main/java/ro/myfinance/common/messaging/DefaultMessageTemplates.java`:

```java
package ro.myfinance.common.messaging;

/**
 * The built-in wording for every message kind — the text clients receive until their accounting firm
 * edits it. Kept in code rather than seeded into the database so it stays one source of truth: a firm
 * that never customizes has no row, "reset to default" is a DELETE, and improving a default improves it
 * for every such firm at once.
 *
 * <p>Changing any string here changes what real clients receive. {@code DefaultMessageTemplatesTest}
 * pins the tax, report and payroll bodies against the pre-template wording byte for byte.
 */
public final class DefaultMessageTemplates {

    public record Template(String subject, String body) {
    }

    private DefaultMessageTemplates() {
    }

    private static final Template TAX = new Template("Sume de plată — {{month}}", """
            Bună ziua,

            Sumele de plată pentru luna {{month}} aferente firmei {{companyName}} sunt următoarele:

            {{paymentSummary}}
            Conturi:
            {{paymentAccounts}}

            O zi plăcută,
            {{signOff}}
            """);

    private static final Template REPORT = new Template("Raport financiar — {{month}}", """
            Bună ziua,

            Vă transmitem atașat raportul financiar pentru luna {{month}}.

            Pe scurt:
            • Venituri: {{revenue}} RON
            • Cheltuieli: {{expenses}} RON
            • Profit net: {{netProfit}} RON

            Detaliile complete și graficele se găsesc în documentele atașate.

            O zi bună,
            {{signOff}}""");

    private static final Template PAYROLL = new Template("State de plată — {{month}}", """
            Bună ziua,

            Vă rog frumos să regăsiți atașate statul de plată, fluturașul de salariu și pontajul pentru luna {{month}}.

            Restul de plată de pe statul de plată și fluturaș trebuie virat din contul firmei în contul personal al salariatului.

            Diferența dintre restul de plată și salariul brut reprezintă taxele salariale. Acestea se vor plăti până în data de {{taxDeadline}}.

            În ceea ce privește sumele care se plătesc către Trezorerie, precum și conturile aferente, vom reveni cu un email separat.

            O zi bună,
            {{signOff}}""");

    private static final String REMINDER_SUBJECT = "Documente lipsă — {{month}}";

    private static final Template REMINDER_NOTHING_RECEIVED = new Template(REMINDER_SUBJECT, """
            Bună ziua,

            Pentru luna {{month}} mai avem nevoie de extrasul de cont și de facturile / chitanțele aferente.

            Vă rugăm să le încărcați în portal.

            Mulțumim,
            {{signOff}}""");

    private static final Template REMINDER_DOCS_MISSING = new Template(REMINDER_SUBJECT, """
            Bună ziua,

            Pentru luna {{month}} mai avem nevoie de documentele justificative (factură/chitanță) pentru următoarele tranzacții:
            {{missingTransactions}}

            Vă rugăm să le încărcați în portal.

            Mulțumim,
            {{signOff}}""");

    private static final Template REMINDER_DOCS_ONLY = new Template(REMINDER_SUBJECT, """
            Bună ziua,

            Pentru luna {{month}} mai avem nevoie de facturile / chitanțele aferente.

            Vă rugăm să le încărcați în portal.

            Mulțumim,
            {{signOff}}""");

    public static Template of(MessageTemplateKind kind) {
        return switch (kind) {
            case TAX -> TAX;
            case REPORT -> REPORT;
            case PAYROLL -> PAYROLL;
            case REMINDER_NOTHING_RECEIVED -> REMINDER_NOTHING_RECEIVED;
            case REMINDER_DOCS_MISSING -> REMINDER_DOCS_MISSING;
            case REMINDER_DOCS_ONLY -> REMINDER_DOCS_ONLY;
        };
    }
}
```

- [ ] **Step 8: Run the golden test and reconcile whitespace**

Run: `cd backend && mvn -q -Dtest=DefaultMessageTemplatesTest test`
Expected: PASS (7 tests).

**This is the step most likely to need iteration**, because block values carry their own line breaks. Two known pressure points:

1. **TAX** — the blank line between `{{paymentAccounts}}` and `O zi plăcută,` exists because each account group starts with an empty line, so the last group does *not* end with one; and the body must end with a trailing newline, which is why the TAX text block's closing `"""` is on its own line while REPORT's and PAYROLL's are not.
2. **`{{paymentSummary}}` sits on its own line directly above `Conturi:`** — the summary block must therefore be joined with `\n` and carry **no** trailing newline.

If a diff appears, change the template constant to match the pinned expectation — never the other way round. The pinned expectation is what clients receive today.

- [ ] **Step 9: Run the full suite**

Run: `cd backend && mvn -q test`
Expected: PASS. `CurrentMessageWordingTest` must still be green — nothing in production changed yet.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/ro/myfinance/common/messaging/DefaultMessageTemplates.java \
        backend/src/main/java/ro/myfinance/common/email/SignOff.java \
        backend/src/test/java/ro/myfinance/messaging/DefaultMessageTemplatesTest.java \
        backend/src/test/java/ro/myfinance/messaging/SignOffNamesTest.java
git commit -m "feat(messaging): add built-in default templates and SignOff.names

Defaults live in code, not seeded rows, so reset-to-default is a DELETE
and improving a default reaches every firm that has not customized.
Golden tests prove each default renders byte-identical to the wording
clients receive today."
```

---

## Task 5: `MessageTemplateService` — tenant row, else default

**Files:**
- Create: `backend/src/main/java/ro/myfinance/common/messaging/RenderedMessage.java`
- Create: `backend/src/main/java/ro/myfinance/common/messaging/MessageTemplateService.java`
- Test: `backend/src/test/java/ro/myfinance/messaging/MessageTemplateServiceTest.java`

**Interfaces:**
- Consumes: `MessageTemplateRepository`, `MessageTemplate` (Task 1); `MessageTemplateKind`, `TemplateRenderer` (Task 2); `DefaultMessageTemplates` (Task 4).
- Produces:
  - `record RenderedMessage(String subject, String body)`.
  - `MessageTemplateService.render(MessageTemplateKind kind, Map<String, String> values) -> RenderedMessage`
  - `MessageTemplateService.effective(MessageTemplateKind kind) -> Effective` where `record Effective(MessageTemplateKind kind, String subject, String body, boolean customized)`
  - `MessageTemplateService.effectiveAll() -> List<Effective>`
  - `MessageTemplateService.save(MessageTemplateKind kind, String subject, String body)`
  - `MessageTemplateService.reset(MessageTemplateKind kind)`

- [ ] **Step 1: Write the failing service test**

Create `backend/src/test/java/ro/myfinance/messaging/MessageTemplateServiceTest.java`:

```java
package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.messaging.MessageTemplate;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.MessageTemplateRepository;
import ro.myfinance.common.messaging.MessageTemplateService;
import ro.myfinance.common.messaging.TemplateRenderer;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;

class MessageTemplateServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("99999999-9999-9999-9999-999999999999");

    private MessageTemplateRepository repo;
    private MessageTemplateService service;

    @BeforeEach
    void setUp() {
        repo = mock(MessageTemplateRepository.class);
        service = new MessageTemplateService(repo, new TemplateRenderer());
        TenantContext.set(new TenantContext.Identity(TENANT, USER, Role.TENANT_ADMIN, null));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void rendersTheBuiltInDefaultWhenNoRowExists() {
        when(repo.findByTenantIdAndKind(TENANT, MessageTemplateKind.PAYROLL)).thenReturn(Optional.empty());
        var out = service.render(MessageTemplateKind.PAYROLL,
                Map.of("month", "Aprilie 2026", "taxDeadline", "25 Aprilie 2026", "signOff", "Firma SRL"));
        assertThat(out.subject()).isEqualTo("State de plată — Aprilie 2026");
        assertThat(out.body()).contains("pontajul pentru luna Aprilie 2026").endsWith("Firma SRL");
    }

    @Test
    void rendersTheTenantRowWhenPresent() {
        MessageTemplate row = new MessageTemplate(TENANT, MessageTemplateKind.PAYROLL);
        row.setSubject("Salarii {{month}}");
        row.setBody("Salut!\nLuna {{month}}.\n{{signOff}}");
        when(repo.findByTenantIdAndKind(TENANT, MessageTemplateKind.PAYROLL)).thenReturn(Optional.of(row));
        var out = service.render(MessageTemplateKind.PAYROLL,
                Map.of("month", "Aprilie 2026", "signOff", "Firma SRL"));
        assertThat(out.subject()).isEqualTo("Salarii Aprilie 2026");
        assertThat(out.body()).isEqualTo("Salut!\nLuna Aprilie 2026.\nFirma SRL");
    }

    /** A tenant may reword the body only; a null subject keeps the built-in one. */
    @Test
    void nullSubjectOnTheRowFallsBackToTheDefaultSubject() {
        MessageTemplate row = new MessageTemplate(TENANT, MessageTemplateKind.REPORT);
        row.setSubject(null);
        row.setBody("Raport {{month}}");
        when(repo.findByTenantIdAndKind(TENANT, MessageTemplateKind.REPORT)).thenReturn(Optional.of(row));
        assertThat(service.render(MessageTemplateKind.REPORT, Map.of("month", "Aprilie 2026")).subject())
                .isEqualTo("Raport financiar — Aprilie 2026");
    }

    @Test
    void effectiveReportsWhetherTheKindIsCustomized() {
        when(repo.findByTenantIdAndKind(TENANT, MessageTemplateKind.TAX)).thenReturn(Optional.empty());
        assertThat(service.effective(MessageTemplateKind.TAX).customized()).isFalse();

        MessageTemplate row = new MessageTemplate(TENANT, MessageTemplateKind.TAX);
        row.setBody("Al nostru");
        when(repo.findByTenantIdAndKind(TENANT, MessageTemplateKind.TAX)).thenReturn(Optional.of(row));
        var eff = service.effective(MessageTemplateKind.TAX);
        assertThat(eff.customized()).isTrue();
        assertThat(eff.body()).isEqualTo("Al nostru");
    }

    @Test
    void effectiveAllReturnsEveryKind() {
        when(repo.findByTenantId(TENANT)).thenReturn(List.of());
        assertThat(service.effectiveAll())
                .hasSize(MessageTemplateKind.values().length)
                .allSatisfy(e -> assertThat(e.customized()).isFalse());
    }

    @Test
    void saveStampsTheCurrentUserAndNormalizesLineEndings() {
        when(repo.findByTenantIdAndKind(TENANT, MessageTemplateKind.PAYROLL)).thenReturn(Optional.empty());
        service.save(MessageTemplateKind.PAYROLL, "Subiect", "Prima\r\nA doua");
        var captor = org.mockito.ArgumentCaptor.forClass(MessageTemplate.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getBody()).isEqualTo("Prima\nA doua");
        assertThat(captor.getValue().getUpdatedBy()).isEqualTo(USER);
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
    }

    @Test
    void resetDeletesTheTenantRow() {
        service.reset(MessageTemplateKind.PAYROLL);
        verify(repo).deleteByTenantIdAndKind(TENANT, MessageTemplateKind.PAYROLL);
    }

    @Test
    void failsClosedWhenNoTenantIsBound() {
        TenantContext.clear();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.render(MessageTemplateKind.TAX, Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

**Verified:** `Role` is `{SUPER_ADMIN, TENANT_ADMIN, EMPLOYEE, REPRESENTATIVE}` and `TenantContext.Identity` is `record Identity(UUID tenantId, UUID userId, Role role, UUID companyId)` — the four-argument form above is correct as written.

- [ ] **Step 2: Run to confirm it fails**

Run: `cd backend && mvn -q -Dtest=MessageTemplateServiceTest test`
Expected: FAIL — `cannot find symbol: class MessageTemplateService`.

- [ ] **Step 3: Add `RenderedMessage`**

Create `backend/src/main/java/ro/myfinance/common/messaging/RenderedMessage.java`:

```java
package ro.myfinance.common.messaging;

/**
 * A message ready to send: subject and body, both already rendered from the tenant's template (or the
 * built-in default). The body is still editable by the user before sending — this is the pre-fill.
 */
public record RenderedMessage(String subject, String body) {
}
```

- [ ] **Step 4: Implement the service**

Create `backend/src/main/java/ro/myfinance/common/messaging/MessageTemplateService.java`:

```java
package ro.myfinance.common.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;

/**
 * Resolves and renders the wording for a message kind: the current tenant's row when it has one, the
 * built-in {@link DefaultMessageTemplates} otherwise. A tenant may reword the body alone — a null
 * subject on the row keeps the built-in subject.
 *
 * <p>All reads and writes are scoped to the bound tenant and additionally enforced by RLS. With no
 * tenant bound this fails closed rather than reading another firm's wording.
 */
@Service
@Transactional
public class MessageTemplateService {

    private final MessageTemplateRepository repo;
    private final TemplateRenderer renderer;

    public MessageTemplateService(MessageTemplateRepository repo, TemplateRenderer renderer) {
        this.repo = repo;
        this.renderer = renderer;
    }

    /** A kind's current wording plus whether this tenant has overridden it. */
    public record Effective(MessageTemplateKind kind, String subject, String body, boolean customized) {
    }

    @Transactional(readOnly = true)
    public RenderedMessage render(MessageTemplateKind kind, Map<String, String> values) {
        Effective e = effective(kind);
        return new RenderedMessage(renderer.render(e.subject(), values), renderer.render(e.body(), values));
    }

    @Transactional(readOnly = true)
    public Effective effective(MessageTemplateKind kind) {
        DefaultMessageTemplates.Template fallback = DefaultMessageTemplates.of(kind);
        Optional<MessageTemplate> row = repo.findByTenantIdAndKind(tenant(), kind);
        return row.map(r -> new Effective(kind,
                        r.getSubject() == null || r.getSubject().isBlank() ? fallback.subject() : r.getSubject(),
                        r.getBody(), true))
                .orElseGet(() -> new Effective(kind, fallback.subject(), fallback.body(), false));
    }

    /** Every kind, for the settings screen — customized rows merged over the defaults. */
    @Transactional(readOnly = true)
    public List<Effective> effectiveAll() {
        Map<MessageTemplateKind, MessageTemplate> rows = new java.util.EnumMap<>(MessageTemplateKind.class);
        for (MessageTemplate r : repo.findByTenantId(tenant())) {
            rows.put(r.getKind(), r);
        }
        List<Effective> out = new ArrayList<>();
        for (MessageTemplateKind kind : MessageTemplateKind.values()) {
            DefaultMessageTemplates.Template fallback = DefaultMessageTemplates.of(kind);
            MessageTemplate r = rows.get(kind);
            out.add(r == null
                    ? new Effective(kind, fallback.subject(), fallback.body(), false)
                    : new Effective(kind,
                            r.getSubject() == null || r.getSubject().isBlank()
                                    ? fallback.subject() : r.getSubject(),
                            r.getBody(), true));
        }
        return out;
    }

    /** Upsert this tenant's wording. Validation happens in the web layer before this is called. */
    public void save(MessageTemplateKind kind, String subject, String body) {
        UUID tenantId = tenant();
        MessageTemplate row = repo.findByTenantIdAndKind(tenantId, kind)
                .orElseGet(() -> new MessageTemplate(tenantId, kind));
        row.setSubject(trimToNull(TemplateRenderer.normalize(subject)));
        row.setBody(TemplateRenderer.normalize(body));
        row.setUpdatedBy(TenantContext.current().map(TenantContext.Identity::userId).orElse(null));
        repo.save(row);
    }

    /** Drop the override, so this kind renders the built-in default again. */
    public void reset(MessageTemplateKind kind) {
        repo.deleteByTenantIdAndKind(tenant(), kind);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static UUID tenant() {
        return TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound — refusing to resolve templates"));
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd backend && mvn -q -Dtest=MessageTemplateServiceTest test`
Expected: PASS (8 tests).

- [ ] **Step 6: Run the full suite**

Run: `cd backend && mvn -q test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/ro/myfinance/common/messaging/MessageTemplateService.java \
        backend/src/main/java/ro/myfinance/common/messaging/RenderedMessage.java \
        backend/src/test/java/ro/myfinance/messaging/MessageTemplateServiceTest.java
git commit -m "feat(messaging): resolve tenant template with default fallback

effective() returns the tenant's row or the built-in default and reports
which; a null subject on a row keeps the built-in subject so a firm can
reword the body alone. Fails closed when no tenant is bound."
```

---

## Task 6: Route the tax email through templates

**Files:**
- Modify: `backend/src/main/java/ro/myfinance/taxpayments/application/PaymentEmailBuilder.java`
- Modify: `backend/src/main/java/ro/myfinance/taxpayments/application/TaxPaymentService.java` (`Computation` record ~line 74, `compute(...)` ~line 295)
- Modify: `backend/src/main/java/ro/myfinance/taxpayments/application/TaxEmailService.java:82`
- Test: `backend/src/test/java/ro/myfinance/messaging/TaxTemplateWiringTest.java`

**Interfaces:**
- Consumes: `MessageTemplateService.render(...)`, `RenderedMessage` (Task 5); `SignOff.names` (Task 4).
- Produces:
  - `PaymentEmailBuilder.summaryBlock(List<PaymentLine>) -> String`
  - `PaymentEmailBuilder.accountsBlock(List<PaymentLine>, String treasury, String cui) -> String`
  - `PaymentEmailBuilder.values(String companyName, String cui, YearMonth period, String treasury, List<PaymentLine> lines, String senderName, String firmName) -> Map<String, String>`
  - `TaxPaymentService.Computation` gains a `String subject` component, appended last: `Computation(String beneficiary, List<PaymentLine> lines, List<Unconfigured> unconfigured, BigDecimal total, String body, String subject)`.

- [ ] **Step 1: Write the failing wiring test**

Create `backend/src/test/java/ro/myfinance/messaging/TaxTemplateWiringTest.java`:

```java
package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import ro.myfinance.taxpayments.application.PaymentEmailBuilder;
import ro.myfinance.taxpayments.domain.PaymentLine;
import ro.myfinance.taxpayments.domain.TaxCategory;

/**
 * The tax builder's remaining job: render the two blocks and hand the renderer a variable map. The
 * blocks stay system-owned so a tenant can reposition the account list but never reformat an IBAN line.
 */
class TaxTemplateWiringTest {

    private final PaymentEmailBuilder builder = new PaymentEmailBuilder();

    private static final PaymentLine TVA = new PaymentLine(
            "RO11TREZ1112345678901234", new BigDecimal("1250.00"), List.of(TaxCategory.TVA),
            "TVA Aprilie-2026", LocalDate.of(2026, 4, 25));

    @Test
    void summaryBlockIsOneLabelledAmountPerLineWithoutTrailingNewline() {
        assertThat(builder.summaryBlock(List.of(TVA))).isEqualTo("TVA: 1250");
    }

    @Test
    void accountsBlockStartsEachGroupWithABlankLine() {
        String out = builder.accountsBlock(List.of(TVA), "Trezoreria Cluj Napoca", "RO123456");
        assertThat(out).isEqualTo(String.join("\n",
                "",
                "- 1250 lei in contul RO11TREZ1112345678901234",
                "- Beneficiar: Trezoreria Cluj Napoca",
                "- CUI Beneficiar: RO123456",
                "- Explicație: TVA Aprilie-2026",
                "- Scadență: 25 aprilie 2026"));
    }

    @Test
    void accountsBlockOmitsTheDueDateLineWhenThereIsNone() {
        PaymentLine noDue = new PaymentLine("RO22TREZ2212345678901234", new BigDecimal("300"),
                List.of(TaxCategory.TVA), "TVA Aprilie-2026", null);
        assertThat(builder.accountsBlock(List.of(noDue), "Trezoreria Cluj Napoca", "RO123456"))
                .doesNotContain("Scadență");
    }

    @Test
    void valuesCarryEveryNameTheTaxKindDeclares() {
        var values = builder.values("Acme SRL", "RO123456", YearMonth.of(2026, 4),
                "Trezoreria Cluj Napoca", List.of(TVA), "Maria Popescu", "ContaZone SRL");
        assertThat(values).containsKeys("companyName", "cui", "month", "treasury", "totalToPay",
                "paymentSummary", "paymentAccounts", "signOff");
        assertThat(values.get("month")).isEqualTo("Aprilie 2026");
        assertThat(values.get("totalToPay")).isEqualTo("1250");
        assertThat(values.get("signOff")).isEqualTo("Maria Popescu\nContaZone SRL");
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd backend && mvn -q -Dtest=TaxTemplateWiringTest test`
Expected: FAIL — `cannot find symbol: method summaryBlock`.

- [ ] **Step 3: Rework `PaymentEmailBuilder`**

Keep the class a `@Component`, keep `shortLabel`, `plain` and `formatRo` exactly as they are, **delete** `build(...)` and the prose, and add:

```java
    /** The summary list: one labelled amount per payment line. No trailing newline — it is one block. */
    public String summaryBlock(List<PaymentLine> lines) {
        return lines.stream()
                .map(l -> shortLabel(l.categories()) + ": " + plain(l))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /**
     * The per-IBAN detail list. Each group is preceded by a blank line, which is what separates the
     * groups from each other and from the {@code Conturi:} heading above them.
     */
    public String accountsBlock(List<PaymentLine> lines, String treasury, String cui) {
        List<String> out = new java.util.ArrayList<>();
        for (PaymentLine l : lines) {
            out.add("");
            out.add("- " + plain(l) + " lei in contul " + l.iban());
            out.add("- Beneficiar: " + treasury);
            out.add("- CUI Beneficiar: " + cui);
            out.add("- Explicație: " + l.explanation());
            if (l.scadenta() != null) {
                out.add("- Scadență: " + formatRo(l.scadenta()));
            }
        }
        return String.join("\n", out);
    }

    /** Everything a {@code TAX} template may reference. Money stays formatted here, never in a template. */
    public java.util.Map<String, String> values(String companyName, String cui, YearMonth period,
                                                String treasury, List<PaymentLine> lines,
                                                String senderName, String firmName) {
        java.math.BigDecimal total = lines.stream()
                .map(PaymentLine::amount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        java.util.Map<String, String> v = new java.util.HashMap<>();
        v.put("companyName", companyName == null ? "" : companyName);
        v.put("cui", cui == null ? "" : cui);
        v.put("month", ro.myfinance.common.i18n.RomanianMonths.monthYear(period));
        v.put("treasury", treasury == null ? "" : treasury);
        v.put("totalToPay", total.stripTrailingZeros().toPlainString());
        v.put("paymentSummary", summaryBlock(lines));
        v.put("paymentAccounts", accountsBlock(lines, treasury, cui));
        v.put("signOff", ro.myfinance.common.email.SignOff.names(senderName, firmName));
        return v;
    }
```

`monthYearSpace` is no longer needed — `RomanianMonths.monthYear(YearMonth)` already returns the space form. Delete it.

- [ ] **Step 4: Run to verify the wiring test passes**

Run: `cd backend && mvn -q -Dtest=TaxTemplateWiringTest test`
Expected: PASS (4 tests). `CurrentMessageWordingTest.taxBodyIsUnchanged` will now fail to compile because `build(...)` is gone — that is expected and fixed in Step 5.

- [ ] **Step 5: Repoint the characterization test at the template path**

In `CurrentMessageWordingTest`, replace the body of `taxBodyIsUnchanged` so it renders the default template with the builder's own values — keeping the same `EXPECTED_TAX_BODY` assertion:

```java
    @Test
    void taxBodyIsUnchanged() {
        var lines = java.util.List.of(
                new ro.myfinance.taxpayments.domain.PaymentLine(
                        "RO11TREZ1112345678901234", new java.math.BigDecimal("1250.00"),
                        java.util.List.of(ro.myfinance.taxpayments.domain.TaxCategory.TVA),
                        "TVA Aprilie-2026", LocalDate.of(2026, 4, 25)),
                new ro.myfinance.taxpayments.domain.PaymentLine(
                        "RO22TREZ2212345678901234", new java.math.BigDecimal("300"),
                        java.util.List.of(ro.myfinance.taxpayments.domain.TaxCategory.IMPOZIT_PROFIT),
                        "Impozite Aprilie-2026", null));
        var values = new ro.myfinance.taxpayments.application.PaymentEmailBuilder().values(
                "Acme SRL", "RO123456", java.time.YearMonth.of(2026, 4), "Trezoreria Cluj Napoca",
                lines, SENDER, FIRM);
        String body = new TemplateRenderer().render(
                DefaultMessageTemplates.of(MessageTemplateKind.TAX).body(), values);
        assertThat(body).isEqualTo(EXPECTED_TAX_BODY);
    }
```

- [ ] **Step 6: Run both tests**

Run: `cd backend && mvn -q -Dtest=CurrentMessageWordingTest,TaxTemplateWiringTest,DefaultMessageTemplatesTest test`
Expected: PASS. The tax wording is now produced by the template and still byte-identical.

- [ ] **Step 7: Wire `TaxPaymentService.compute`**

Inject `MessageTemplateService templates` into `TaxPaymentService` (constructor parameter + field, following the existing injection style). Add `String subject` as the last component of the `Computation` record, then replace the body-building tail of `compute(...)`:

```java
        String beneficiary = beneficiary(company.getLocality());
        YearMonth emailPeriod = period == null ? null : YearMonth.from(period);
        String body = null;
        String subject = null;
        if (beneficiary != null && !configured.isEmpty() && emailPeriod != null) {
            var values = emailBuilder.values(company.getLegalName(), company.getCui(), emailPeriod,
                    beneficiary, configured, envelopes.currentUserName(), envelopes.firmName());
            RenderedMessage msg = templates.render(MessageTemplateKind.TAX, values);
            body = msg.body();
            subject = msg.subject();
        }
        return new Computation(beneficiary, configured, unconfigured, total, body, subject);
```

Fix every other `new Computation(...)` call site the compiler flags by passing `null` for the new `subject` component where no email is composed.

- [ ] **Step 8: Use the rendered subject when sending**

In `TaxEmailService`, delete the private `subject(UUID, LocalDate)` method and change `send(...)`:

```java
    public EmailHistory send(UUID companyId, LocalDate period, List<UUID> declarationIds, String recipient, String body) {
        LocalDate month = period.withDayOfMonth(1);
        Computation computed = payments.composeFor(companyId, declarationIds);
        String subject = computed.subject() != null
                ? computed.subject()
                : "Sume de plată — " + ro.myfinance.common.i18n.RomanianMonths.monthYear(YearMonth.from(month));
        return dispatch.dispatch(EmailKind.TAX, companyId, period, recipient, subject, body, null, declarationIds,
                () -> notifyTaxDue(companyId, month, declarationIds));
    }
```

The fallback covers the case where no treasury IBAN is configured, so a send is still recorded with a sensible subject rather than null.

- [ ] **Step 9: Run the tax module tests**

Run: `cd backend && mvn -q test`
Expected: PASS. Any `TaxPaymentServiceTest` that constructs the service needs the new `MessageTemplateService` argument — pass a real one built from a mocked repository plus `new TemplateRenderer()`, so the default template renders.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/ro/myfinance/taxpayments/ \
        backend/src/test/java/ro/myfinance/messaging/TaxTemplateWiringTest.java \
        backend/src/test/java/ro/myfinance/messaging/CurrentMessageWordingTest.java
git commit -m "refactor(taxpayments): render the tax email from a template

PaymentEmailBuilder keeps the two block renderers and the money
formatting and drops the prose, which now lives in the default template.
Subject comes from the template too, with a fallback for companies that
have no treasury IBAN configured."
```

---

## Task 7: Route the report email through templates

**Files:**
- Modify: `backend/src/main/java/ro/myfinance/reports/application/ReportEmailBuilder.java`
- Modify: `backend/src/main/java/ro/myfinance/reports/application/ReportEmailService.java:55`
- Test: `backend/src/test/java/ro/myfinance/messaging/ReportTemplateWiringTest.java`

**Interfaces:**
- Consumes: `MessageTemplateService`, `RenderedMessage`, `SignOff.names`.
- Produces: `ReportEmailBuilder.values(LocalDate period, ReportData r, String senderName, String firmName) -> Map<String, String>`. `ReportEmailBuilder.body(...)` and `.subject(...)` are **deleted**; `ReportEmailService.composeBody(...)` keeps its name and signature and still returns `String` (the body), so `ReportController` is untouched.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/ro/myfinance/messaging/ReportTemplateWiringTest.java`:

```java
package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.reports.application.ReportEmailBuilder;

class ReportTemplateWiringTest {

    @Test
    void valuesCarryEveryFigureTheReportKindDeclares() {
        var values = ReportEmailBuilder.values(LocalDate.of(2026, 4, 1),
                CurrentMessageWordingTest.reportData(), "Maria Popescu", "ContaZone SRL");
        assertThat(values).containsKeys("companyName", "cui", "month", "revenue", "expenses",
                "grossProfit", "incomeTax", "netProfit", "signOff");
        assertThat(values.get("month")).isEqualTo("Aprilie 2026");
        assertThat(values.get("revenue")).isEqualTo("100 000.00");
        assertThat(values.get("expenses")).isEqualTo("61 000.00");
        assertThat(values.get("netProfit")).isEqualTo("39 000.00");
        assertThat(values.get("companyName")).isEqualTo("Acme SRL");
    }

    @Test
    void expensesAreOperatingPlusIncomeTax() {
        var values = ReportEmailBuilder.values(LocalDate.of(2026, 4, 1),
                CurrentMessageWordingTest.reportData(), null, "ContaZone SRL");
        assertThat(values.get("expenses")).isEqualTo("61 000.00");
        assertThat(values.get("incomeTax")).isEqualTo("1 000.00");
        assertThat(values.get("signOff")).isEqualTo("ContaZone SRL");
    }
}
```

Change `reportData()` in `CurrentMessageWordingTest` from `private static` to `static` so this test can reuse the fixture.

- [ ] **Step 2: Run to confirm it fails**

Run: `cd backend && mvn -q -Dtest=ReportTemplateWiringTest test`
Expected: FAIL — `cannot find symbol: method values`.

- [ ] **Step 3: Rework `ReportEmailBuilder`**

Replace `body(...)` and `subject(...)` with:

```java
    /** Everything a {@code REPORT} template may reference. Figures come straight from the report. */
    public static java.util.Map<String, String> values(LocalDate period, ReportData r,
                                                       String senderName, String firmName) {
        var pl = r.profitLoss();
        java.util.Map<String, String> v = new java.util.HashMap<>();
        v.put("companyName", r.companyName() == null ? "" : r.companyName());
        v.put("cui", r.cui() == null ? "" : r.cui());
        v.put("month", monthYear(period.withDayOfMonth(1)));
        v.put("revenue", ReportPdfGenerator.money(pl.revenue()));
        v.put("expenses", ReportPdfGenerator.money(pl.operatingExpenses().add(pl.incomeTax())));
        v.put("grossProfit", ReportPdfGenerator.money(pl.grossProfit()));
        v.put("incomeTax", ReportPdfGenerator.money(pl.incomeTax()));
        v.put("netProfit", ReportPdfGenerator.money(pl.netProfit()));
        v.put("signOff", ro.myfinance.common.email.SignOff.names(senderName, firmName));
        return v;
    }
```

Keep `monthYear(LocalDate)` — it is used here and may be used elsewhere.

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && mvn -q -Dtest=ReportTemplateWiringTest test`
Expected: PASS (2 tests).

- [ ] **Step 5: Repoint the report characterization test**

In `CurrentMessageWordingTest`, rewrite the two report tests to go through the template:

```java
    @Test
    void reportBodyIsUnchanged() {
        var values = ro.myfinance.reports.application.ReportEmailBuilder
                .values(APRIL, reportData(), SENDER, FIRM);
        assertThat(new TemplateRenderer().render(
                DefaultMessageTemplates.of(MessageTemplateKind.REPORT).body(), values))
                .isEqualTo(EXPECTED_REPORT_BODY);
    }

    @Test
    void reportSubjectIsUnchanged() {
        var values = ro.myfinance.reports.application.ReportEmailBuilder
                .values(APRIL, reportData(), SENDER, FIRM);
        assertThat(new TemplateRenderer().render(
                DefaultMessageTemplates.of(MessageTemplateKind.REPORT).subject(), values))
                .isEqualTo("Raport financiar — Aprilie 2026");
    }
```

- [ ] **Step 6: Wire `ReportEmailService`**

Inject `MessageTemplateService templates`, then:

```java
    @Transactional(readOnly = true)
    public String composeBody(UUID companyId, LocalDate period) {
        return message(companyId, period).body();
    }

    /** Subject + body for a company/period, rendered from this tenant's REPORT template. */
    @Transactional(readOnly = true)
    public RenderedMessage message(UUID companyId, LocalDate period) {
        ReportData r = reports.report(companyId, period);
        return templates.render(MessageTemplateKind.REPORT, ReportEmailBuilder.values(
                period.withDayOfMonth(1), r, envelopes.currentUserName(), envelopes.firmName()));
    }
```

Then find where `send(...)` sets the report subject and change it to `message(companyId, period).subject()`. Search with `grep -n 'Raport financiar\|subject' backend/src/main/java/ro/myfinance/reports/application/ReportEmailService.java`.

- [ ] **Step 7: Run the suite**

Run: `cd backend && mvn -q test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/ro/myfinance/reports/ \
        backend/src/test/java/ro/myfinance/messaging/ReportTemplateWiringTest.java \
        backend/src/test/java/ro/myfinance/messaging/CurrentMessageWordingTest.java
git commit -m "refactor(reports): render the report email from a template

ReportEmailBuilder becomes a variable-map assembler; money formatting
stays in Java. composeBody keeps its signature so the controller is
untouched."
```

---

## Task 8: Route the payroll email through templates

**Files:**
- Modify: `backend/src/main/java/ro/myfinance/payroll/application/PayrollEmailBuilder.java`
- Modify: `backend/src/main/java/ro/myfinance/payroll/application/PayrollService.java:189`
- Test: `backend/src/test/java/ro/myfinance/messaging/PayrollTemplateWiringTest.java`

**Interfaces:**
- Consumes: `MessageTemplateService`, `RenderedMessage`, `SignOff.names`.
- Produces: `PayrollEmailBuilder.values(LocalDate period, String companyName, String senderName, String firmName) -> Map<String, String>`; `PayrollService.message(UUID, LocalDate) -> RenderedMessage` alongside the unchanged `composeBody(UUID, LocalDate) -> String`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/ro/myfinance/messaging/PayrollTemplateWiringTest.java`:

```java
package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.payroll.application.PayrollEmailBuilder;

class PayrollTemplateWiringTest {

    @Test
    void valuesCarryTheMonthAndTheTwentyFifthDeadline() {
        var values = PayrollEmailBuilder.values(LocalDate.of(2026, 4, 17), "Acme SRL",
                "Maria Popescu", "ContaZone SRL");
        assertThat(values.get("month")).isEqualTo("Aprilie 2026");
        assertThat(values.get("taxDeadline")).isEqualTo("25 Aprilie 2026");
        assertThat(values.get("companyName")).isEqualTo("Acme SRL");
        assertThat(values.get("signOff")).isEqualTo("Maria Popescu\nContaZone SRL");
    }

    @Test
    void normalizesAnyDayOfMonthToTheFirst() {
        assertThat(PayrollEmailBuilder.values(LocalDate.of(2026, 12, 31), null, null, "Firma")
                .get("month")).isEqualTo("Decembrie 2026");
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd backend && mvn -q -Dtest=PayrollTemplateWiringTest test`
Expected: FAIL — `cannot find symbol: method values`.

- [ ] **Step 3: Rework `PayrollEmailBuilder`**

Replace `body(...)` and `subject(...)` with:

```java
    /** Everything a {@code PAYROLL} template may reference. The tax deadline is the 25th of the period. */
    public static java.util.Map<String, String> values(LocalDate period, String companyName,
                                                       String senderName, String firmName) {
        LocalDate p = period.withDayOfMonth(1);
        String my = monthYear(p);
        java.util.Map<String, String> v = new java.util.HashMap<>();
        v.put("companyName", companyName == null ? "" : companyName);
        v.put("month", my);
        v.put("taxDeadline", "25 " + my);
        v.put("signOff", ro.myfinance.common.email.SignOff.names(senderName, firmName));
        return v;
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && mvn -q -Dtest=PayrollTemplateWiringTest test`
Expected: PASS (2 tests).

- [ ] **Step 5: Repoint the payroll characterization tests**

In `CurrentMessageWordingTest`, rewrite the three payroll tests to render through the template, keeping `EXPECTED_PAYROLL_BODY`:

```java
    private static String renderPayroll(String sender) {
        var values = ro.myfinance.payroll.application.PayrollEmailBuilder
                .values(APRIL, "Acme SRL", sender, FIRM);
        return new TemplateRenderer().render(
                DefaultMessageTemplates.of(MessageTemplateKind.PAYROLL).body(), values);
    }

    @Test
    void payrollBodyIsUnchanged() {
        assertThat(renderPayroll(SENDER)).isEqualTo(EXPECTED_PAYROLL_BODY);
    }

    @Test
    void payrollSubjectIsUnchanged() {
        var values = ro.myfinance.payroll.application.PayrollEmailBuilder
                .values(APRIL, "Acme SRL", SENDER, FIRM);
        assertThat(new TemplateRenderer().render(
                DefaultMessageTemplates.of(MessageTemplateKind.PAYROLL).subject(), values))
                .isEqualTo("State de plată — Aprilie 2026");
    }

    @Test
    void payrollSignsWithFirmOnlyWhenSenderUnknown() {
        assertThat(renderPayroll(null)).endsWith("O zi bună,\nContaZone SRL");
    }
```

- [ ] **Step 6: Wire `PayrollService`**

Inject `MessageTemplateService templates`. `PayrollService` needs the company's legal name — it already resolves companies elsewhere in the class; reuse that lookup (`grep -n 'companies\.' backend/src/main/java/ro/myfinance/payroll/application/PayrollService.java` to find the repository field and its accessor) and pass `null` for `companyName` only if no lookup exists, since the default template does not reference it.

```java
    @Transactional(readOnly = true)
    public String composeBody(UUID companyId, LocalDate period) {
        return message(companyId, period).body();
    }

    /** Subject + body for a company/period, rendered from this tenant's PAYROLL template. */
    @Transactional(readOnly = true)
    public RenderedMessage message(UUID companyId, LocalDate period) {
        return templates.render(MessageTemplateKind.PAYROLL, PayrollEmailBuilder.values(
                period, companyName(companyId), envelopes.currentUserName(), envelopes.firmName()));
    }
```

Then repoint the payroll send's subject to `message(companyId, period).subject()` (`grep -n 'State de plată' backend/src/main/java/ro/myfinance/payroll/application/PayrollService.java`).

- [ ] **Step 7: Run the suite**

Run: `cd backend && mvn -q test`
Expected: PASS. All three characterization tests now prove the template path reproduces the original wording.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/ro/myfinance/payroll/ \
        backend/src/test/java/ro/myfinance/messaging/PayrollTemplateWiringTest.java \
        backend/src/test/java/ro/myfinance/messaging/CurrentMessageWordingTest.java
git commit -m "refactor(payroll): render the payroll email from a template

Completes the three byte-identical refactors: tax, report and payroll
wording now all come from templates, pinned by characterization tests."
```

---

## Task 9: Server-side reminder state selection and body endpoint

**Files:**
- Create: `backend/src/main/java/ro/myfinance/extraction/application/ReminderBodyService.java`
- Modify: `backend/src/main/java/ro/myfinance/extraction/application/DocumentReminderService.java:105`
- Modify: `backend/src/main/java/ro/myfinance/extraction/adapter/web/DocumentReminderController.java`
- Test: `backend/src/test/java/ro/myfinance/messaging/ReminderStateSelectionTest.java`

**Interfaces:**
- Consumes: `MessageTemplateService`, `RenderedMessage`, `MessageTemplateKind`; `DocumentService.listByCompanyPeriodType(UUID, LocalDate, DocumentType)`; `ReconciliationService.transactionsWithMatches(UUID, LocalDate)`; `EmailEnvelopeService.currentUserName()/firmName()`.
- Produces:
  - `ReminderBodyService.ReminderMessage` — `record ReminderMessage(MessageTemplateKind state, String subject, String body)`
  - `ReminderBodyService.compose(UUID companyId, LocalDate period) -> ReminderMessage`
  - `ReminderBodyService.stateFor(UUID companyId, LocalDate period) -> MessageTemplateKind`
  - `ReminderBodyService.missingBlock(List<TxnWithMatches>) -> String`
  - New endpoint `GET /api/v1/companies/{companyId}/document-reminders/body?period=YYYY-MM-DD` → `{state, subject, body}`

State rule (spec §9): no bank statement → `REMINDER_NOTHING_RECEIVED`; statement present with ≥1 transaction that requires a document and has no matched invoice → `REMINDER_DOCS_MISSING`; statement present, none missing → `REMINDER_DOCS_ONLY`.

- [ ] **Step 1: Write the failing state-selection test**

Create `backend/src/test/java/ro/myfinance/messaging/ReminderStateSelectionTest.java`:

```java
package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.MessageTemplateRepository;
import ro.myfinance.common.messaging.MessageTemplateService;
import ro.myfinance.common.messaging.TemplateRenderer;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.extraction.application.ReminderBodyService;

/**
 * The three reminder states and the rendered body for each. State selection is the server's job now —
 * the browser used to decide it, which is how the backend notification and the client email drifted apart.
 */
class ReminderStateSelectionTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMPANY = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);

    private ReminderBodyService service;
    // Declare these with the real collaborator types from ReminderBodyService's constructor.
    private Object documents;      // DocumentService
    private Object reconciliation; // ReconciliationService
    private Object envelopes;      // EmailEnvelopeService

    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        MessageTemplateRepository repo = mock(MessageTemplateRepository.class);
        when(repo.findByTenantIdAndKind(any(), any())).thenReturn(Optional.empty());
        // Build the service with mocks for documents/reconciliation/envelopes and a real
        // MessageTemplateService over the mocked repository, so built-in defaults render.
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void noStatementYieldsTheNothingReceivedState() {
        givenNoBankStatement();
        var msg = service.compose(COMPANY, APRIL);
        assertThat(msg.state()).isEqualTo(MessageTemplateKind.REMINDER_NOTHING_RECEIVED);
        assertThat(msg.body()).contains("extrasul de cont");
        assertThat(msg.subject()).isEqualTo("Documente lipsă — Aprilie 2026");
    }

    @Test
    void statementPlusMissingDocsYieldsTheDocsMissingStateWithTheList() {
        givenBankStatement();
        givenTransactions(txnMissingDoc(LocalDate.of(2026, 4, 15), "Acme SRL", new BigDecimal("-1234.56")));
        var msg = service.compose(COMPANY, APRIL);
        assertThat(msg.state()).isEqualTo(MessageTemplateKind.REMINDER_DOCS_MISSING);
        assertThat(msg.body()).contains("• 15.04.2026 — Acme SRL — 1.234,56 RON");
    }

    @Test
    void statementWithNothingMissingYieldsTheDocsOnlyState() {
        givenBankStatement();
        givenTransactions(); // none requiring a document
        var msg = service.compose(COMPANY, APRIL);
        assertThat(msg.state()).isEqualTo(MessageTemplateKind.REMINDER_DOCS_ONLY);
        assertThat(msg.body())
                .contains("facturile / chitanțele")
                .doesNotContain("extrasul de cont");
    }

    @Test
    void aTransactionThatAlreadyHasAnInvoiceIsNotListed() {
        givenBankStatement();
        givenTransactions(txnWithInvoice(LocalDate.of(2026, 4, 15), "Acme SRL", new BigDecimal("-100")));
        assertThat(service.compose(COMPANY, APRIL).state())
                .isEqualTo(MessageTemplateKind.REMINDER_DOCS_ONLY);
    }

    @Test
    void aTransactionThatDoesNotRequireADocumentIsNotListed() {
        givenBankStatement();
        givenTransactions(txnNotRequiringDoc(LocalDate.of(2026, 4, 15), "Banca", new BigDecimal("-9")));
        assertThat(service.compose(COMPANY, APRIL).state())
                .isEqualTo(MessageTemplateKind.REMINDER_DOCS_ONLY);
    }

    @Test
    void anUnknownPartnerFallsBackToTheDescriptionThenADash() {
        givenBankStatement();
        givenTransactions(txnMissingDocWithoutPartner(LocalDate.of(2026, 4, 2), "POS 1234",
                new BigDecimal("-50")));
        assertThat(service.compose(COMPANY, APRIL).body()).contains("• 02.04.2026 — POS 1234 — 50,00 RON");
    }
}
```

Write the `given*` and `txn*` helpers against the real collaborator types. Read these three first so the mocks match:
- `grep -n 'record TxnWithMatches\|class TxnWithMatches' -r backend/src/main/java/ro/myfinance/extraction/`
- `grep -n 'isRequiresDocument\|getPartnerName\|getDescription\|getTxnDate\|getAmount' backend/src/main/java/ro/myfinance/extraction/domain/BankTransaction.java`
- `grep -n 'BANK_STATEMENT' backend/src/main/java/ro/myfinance/intake/domain/DocumentType.java`

`givenNoBankStatement()` stubs `documents.listByCompanyPeriodType(COMPANY, APRIL, DocumentType.BANK_STATEMENT)` to `List.of()`; `givenBankStatement()` stubs it to a one-element list (`mock(Document.class)` is fine — only emptiness is read). `givenTransactions(...)` stubs `reconciliation.transactionsWithMatches(COMPANY, APRIL)`. Stub `envelopes.currentUserName()` to `"Maria Popescu"` and `firmName()` to `"ContaZone SRL"`.

- [ ] **Step 2: Run to confirm it fails**

Run: `cd backend && mvn -q -Dtest=ReminderStateSelectionTest test`
Expected: FAIL — `cannot find symbol: class ReminderBodyService`.

- [ ] **Step 3: Implement `ReminderBodyService`**

Create `backend/src/main/java/ro/myfinance/extraction/application/ReminderBodyService.java`:

```java
package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.access.application.EmailEnvelopeService;
import ro.myfinance.common.email.SignOff;
import ro.myfinance.common.i18n.RomanianMonths;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.MessageTemplateService;
import ro.myfinance.common.messaging.RenderedMessage;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Picks which of the three document-reminder states applies to a company/period and renders that state's
 * template. This decision used to live in the browser ({@code reminderBody.ts}), which is why the client
 * email and the in-app notification drifted apart; it belongs here, next to the data.
 *
 * <ul>
 *   <li>no bank statement → {@code REMINDER_NOTHING_RECEIVED} (ask for statement + documents)</li>
 *   <li>statement in, transactions still lacking a document → {@code REMINDER_DOCS_MISSING} (list them)</li>
 *   <li>statement in, nothing missing → {@code REMINDER_DOCS_ONLY} (general ask)</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class ReminderBodyService {

    private static final DateTimeFormatter ROW_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final DocumentService documents;
    private final ReconciliationService reconciliation;
    private final EmailEnvelopeService envelopes;
    private final MessageTemplateService templates;
    private final ro.myfinance.company.adapter.persistence.CompanyRepository companies;

    public ReminderBodyService(DocumentService documents, ReconciliationService reconciliation,
                               EmailEnvelopeService envelopes, MessageTemplateService templates,
                               ro.myfinance.company.adapter.persistence.CompanyRepository companies) {
        this.documents = documents;
        this.reconciliation = reconciliation;
        this.envelopes = envelopes;
        this.templates = templates;
        this.companies = companies;
    }

    /** The chosen state plus its rendered subject and body. The body is editable before sending. */
    public record ReminderMessage(MessageTemplateKind state, String subject, String body) {
    }

    public ReminderMessage compose(UUID companyId, LocalDate period) {
        LocalDate month = period.withDayOfMonth(1);
        List<TxnWithMatches> missing = missingTransactions(companyId, month);
        MessageTemplateKind state = state(hasBankStatement(companyId, month), missing);

        Map<String, String> values = new HashMap<>();
        values.put("companyName", companyName(companyId));
        values.put("month", RomanianMonths.monthYear(month));
        values.put("missingCount", String.valueOf(missing.size()));
        values.put("missingTransactions", missingBlock(missing));
        values.put("signOff", SignOff.names(envelopes.currentUserName(), envelopes.firmName()));

        RenderedMessage msg = templates.render(state, values);
        return new ReminderMessage(state, msg.subject(), msg.body());
    }

    /** Exposed for the reminder screens, which disable the button for a state the user should not send. */
    public MessageTemplateKind stateFor(UUID companyId, LocalDate period) {
        LocalDate month = period.withDayOfMonth(1);
        return state(hasBankStatement(companyId, month), missingTransactions(companyId, month));
    }

    private static MessageTemplateKind state(boolean hasStatement, List<TxnWithMatches> missing) {
        if (!hasStatement) {
            return MessageTemplateKind.REMINDER_NOTHING_RECEIVED;
        }
        return missing.isEmpty()
                ? MessageTemplateKind.REMINDER_DOCS_ONLY
                : MessageTemplateKind.REMINDER_DOCS_MISSING;
    }

    private boolean hasBankStatement(UUID companyId, LocalDate month) {
        return !documents.listByCompanyPeriodType(companyId, month, DocumentType.BANK_STATEMENT).isEmpty();
    }

    /** A transaction that requires an invoice/receipt and has none matched. */
    private List<TxnWithMatches> missingTransactions(UUID companyId, LocalDate month) {
        return reconciliation.transactionsWithMatches(companyId, month).stream()
                .filter(tw -> tw.txn().isRequiresDocument() && tw.invoices().isEmpty())
                .toList();
    }

    /** One row per transaction: {@code • 15.04.2026 — Acme SRL — 1.234,56 RON}. System-owned format. */
    public String missingBlock(List<TxnWithMatches> missing) {
        List<String> rows = new ArrayList<>();
        for (TxnWithMatches tw : missing) {
            var t = tw.txn();
            rows.add("• " + ROW_DATE.format(t.getTxnDate()) + " — " + who(t) + " — "
                    + amount(t.getAmount()) + " RON");
        }
        return String.join("\n", rows);
    }

    private static String who(ro.myfinance.extraction.domain.BankTransaction t) {
        if (t.getPartnerName() != null && !t.getPartnerName().isBlank()) {
            return t.getPartnerName();
        }
        return t.getDescription() != null && !t.getDescription().isBlank() ? t.getDescription() : "—";
    }

    /** Romanian money: dot grouping, comma decimal, always two decimals, absolute value. */
    private static String amount(BigDecimal v) {
        if (v == null) {
            return "0,00";
        }
        DecimalFormatSymbols sym = new DecimalFormatSymbols();
        sym.setGroupingSeparator('.');
        sym.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.00", sym).format(v.abs());
    }

    /**
     * A tenant template MAY reference {{companyName}} even though no reminder default does, so resolve it
     * — but never fail a compose over a display name.
     */
    private String companyName(UUID companyId) {
        try {
            return companies.findById(companyId)
                    .map(ro.myfinance.company.domain.Company::getLegalName)
                    .orElse("");
        } catch (RuntimeException e) {
            return "";
        }
    }
}
```

**Verified:** `EmailEnvelopeService` exposes only `firmName()` and `currentUserName()` — there is **no** `companyName(UUID)`. So inject the company repository instead: add a `CompanyRepository companies` constructor parameter and field, matching how `TaxPaymentService` resolves a company (`grep -n 'CompanyRepository\|companies.findById' backend/src/main/java/ro/myfinance/taxpayments/application/TaxPaymentService.java`), and confirm the entity's accessor is `getLegalName()`.

- [ ] **Step 4: Run to verify the state tests pass**

Run: `cd backend && mvn -q -Dtest=ReminderStateSelectionTest test`
Expected: PASS (6 tests).

- [ ] **Step 5: Write the failing endpoint test**

Create `backend/src/test/java/ro/myfinance/extraction/ReminderBodyEndpointIT.java` as a `@SpringBootTest` + `MockMvc` test following the nearest existing controller IT (find one with `ls backend/src/test/java/ro/myfinance/**/*IT.java`). Assert:

```java
    @Test
    void returnsStateSubjectAndBodyForTheCompanyAndPeriod() throws Exception {
        mvc.perform(get("/api/v1/companies/{id}/document-reminders/body", companyId)
                        .param("period", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REMINDER_NOTHING_RECEIVED"))
                .andExpect(jsonPath("$.subject").value("Documente lipsă — Aprilie 2026"))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("Bună ziua,")));
    }

    @Test
    void representativesCannotReachIt() throws Exception {
        mvc.perform(get("/api/v1/companies/{id}/document-reminders/body", companyId)
                        .param("period", "2026-04-01")
                        .with(repAuth()))
                .andExpect(status().isForbidden());
    }
```

- [ ] **Step 6: Run to confirm it fails**

Run: `cd backend && mvn -q -Dtest=ReminderBodyEndpointIT verify`
Expected: FAIL — 404, the endpoint does not exist.

- [ ] **Step 7: Add the endpoint**

In `DocumentReminderController` (class-level `@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")` already excludes representatives), inject `ReminderBodyService bodies` and add:

```java
    /**
     * The default editable reminder for a company/period: which of the three states applies, plus the
     * subject and body rendered from this tenant's template. The browser no longer composes this.
     */
    @GetMapping("/api/v1/companies/{companyId}/document-reminders/body")
    public ReminderBodyResponse body(@PathVariable UUID companyId,
                                     @RequestParam("period") LocalDate period) {
        var msg = bodies.compose(companyId, period);
        return new ReminderBodyResponse(msg.state().name(), msg.subject(), msg.body());
    }

    public record ReminderBodyResponse(String state, String subject, String body) {
    }
```

- [ ] **Step 8: Run to verify it passes**

Run: `cd backend && mvn -q -Dtest=ReminderBodyEndpointIT verify`
Expected: PASS (2 tests).

- [ ] **Step 9: Use the template subject when sending a reminder**

In `DocumentReminderService`, inject `ReminderBodyService bodies` and replace the hardcoded subject in `send(...)`:

```java
    public ReminderView send(UUID companyId, LocalDate period, String recipient, String body) {
        LocalDate month = period.withDayOfMonth(1);
        String subject = bodies.compose(companyId, month).subject();
        EmailHistory row = dispatch.dispatch(EmailKind.DOCUMENT_REMINDER, companyId, period, recipient,
                subject, body, null, null,
                () -> notifications.notifyCompanyReps(companyId, "DOC_REQUEST", "Documente solicitate",
                        docRequestBody(companyId, month)));
        return ReminderView.from(row);
    }
```

Leave `docRequestBody` and the `MONTH`/`DAY` formatters alone — the in-app notification is deliberately out of scope (spec §12). `MONTH` is still used there.

- [ ] **Step 10: Run the suite**

Run: `cd backend && mvn -q test && mvn -q verify`
Expected: PASS. Reminder subjects are now `Documente lipsă — Aprilie 2026` instead of `Documente lipsă — 04.2026` — the intended change from spec §11.1. Update any existing test that asserts the old `MM.yyyy` subject.

- [ ] **Step 11: Add the large-block test (Review Focus 5)**

Append to `ReminderStateSelectionTest`:

```java
    /** Review Focus 5: a busy month must render completely — no truncation, no quadratic blow-up. */
    @Test
    void rendersTwoHundredMissingTransactionsInFull() {
        givenBankStatement();
        var many = new java.util.ArrayList<Object>(); // typed as List<TxnWithMatches> in real code
        for (int i = 1; i <= 200; i++) {
            many.add(txnMissingDoc(LocalDate.of(2026, 4, 1).plusDays(i % 28),
                    "Partener " + i, new BigDecimal("-100.00")));
        }
        givenTransactionList(many);
        String body = service.compose(COMPANY, APRIL).body();
        assertThat(body.lines().filter(l -> l.startsWith("• ")).count()).isEqualTo(200);
        assertThat(body).contains("Partener 200").endsWith("ContaZone SRL");
    }
```

- [ ] **Step 12: Run and commit**

Run: `cd backend && mvn -q -Dtest=ReminderStateSelectionTest test`
Expected: PASS (7 tests).

```bash
git add backend/src/main/java/ro/myfinance/extraction/ \
        backend/src/test/java/ro/myfinance/messaging/ReminderStateSelectionTest.java \
        backend/src/test/java/ro/myfinance/extraction/ReminderBodyEndpointIT.java
git commit -m "feat(extraction): choose and render the reminder state server-side

Three states (no statement / docs missing / docs only) decided next to
the data instead of in the browser, behind GET .../document-reminders/body.
Reminder subjects now read 'Aprilie 2026' like every other message."
```

---

## Task 10: Settings API, validation, authorization

**Files:**
- Create: `backend/src/main/java/ro/myfinance/settings/application/TemplateValidator.java`
- Create: `backend/src/main/java/ro/myfinance/settings/adapter/web/TemplateSettingsDtos.java`
- Create: `backend/src/main/java/ro/myfinance/settings/adapter/web/TemplateSettingsController.java`
- Test: `backend/src/test/java/ro/myfinance/messaging/TemplateValidatorTest.java`
- Test: `backend/src/test/java/ro/myfinance/settings/TemplateSettingsAuthzIT.java`

**Interfaces:**
- Consumes: `MessageTemplateService` (Task 5), `MessageTemplateKind`, `DefaultMessageTemplates`, `TemplateRenderer`.
- Produces:
  - `TemplateValidator.validate(MessageTemplateKind kind, String subject, String body)` — throws `BadRequestException` on violation, returns void otherwise.
  - Endpoints: `GET /api/v1/settings/messaging/templates`, `PUT /api/v1/settings/messaging/templates/{kind}`, `DELETE /api/v1/settings/messaging/templates/{kind}`, `POST /api/v1/settings/messaging/templates/{kind}/preview`.

- [ ] **Step 1: Write the failing validator test**

Create `backend/src/test/java/ro/myfinance/messaging/TemplateValidatorTest.java`:

```java
package ro.myfinance.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.settings.application.TemplateValidator;

/**
 * Save-time validation is the whole point of the placeholder contract: a typo must fail here, in
 * Settings, rather than reach a client as literal {{compnayName}}.
 */
class TemplateValidatorTest {

    private final TemplateValidator validator = new TemplateValidator();

    @Test
    void acceptsATemplateUsingOnlyDeclaredNames() {
        assertThatCode(() -> validator.validate(MessageTemplateKind.PAYROLL,
                "Salarii {{month}}",
                "Bună ziua,\n\nLuna {{month}}, scadența {{taxDeadline}}.\n\nMulțumim,\n{{signOff}}"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAMisspelledPlaceholder() {
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.REPORT,
                "Raport {{month}}", "Firma {{compnayName}} a avut {{revenue}}."))
                .hasMessageContaining("compnayName");
    }

    @Test
    void rejectsAPlaceholderBelongingToAnotherKind() {
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.PAYROLL,
                "Salarii", "Conturi: {{paymentAccounts}}"))
                .hasMessageContaining("paymentAccounts");
    }

    @Test
    void rejectsABlockInsideASubject() {
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.TAX,
                "Sume {{paymentSummary}}", "Bună ziua, {{signOff}}"))
                .hasMessageContaining("paymentSummary");
    }

    @Test
    void rejectsUnbalancedDelimiters() {
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.PAYROLL,
                "Salarii", "Luna {{month}."))
                .hasMessageContaining("{{");
    }

    @Test
    void rejectsABlankBody() {
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.PAYROLL, "Salarii", "   "))
                .hasMessageContaining("body");
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.PAYROLL, "Salarii", null))
                .hasMessageContaining("body");
    }

    @Test
    void rejectsAnOversizedBodyOrSubject() {
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.PAYROLL, "Salarii",
                "x".repeat(10_001))).hasMessageContaining("10000");
        assertThatThrownBy(() -> validator.validate(MessageTemplateKind.PAYROLL,
                "s".repeat(301), "Bună ziua")).hasMessageContaining("300");
    }

    /** A blank subject is allowed — it means "keep the built-in subject". */
    @Test
    void allowsABlankSubject() {
        assertThatCode(() -> validator.validate(MessageTemplateKind.PAYROLL, "  ", "Bună ziua"))
                .doesNotThrowAnyException();
    }

    /** Review Focus 2, save half: CRLF must not count against the length cap or reach storage. */
    @Test
    void acceptsCrlfAndJudgesLengthAfterNormalization() {
        assertThatCode(() -> validator.validate(MessageTemplateKind.PAYROLL, "Salarii",
                "Prima\r\nA doua\r\n{{signOff}}")).doesNotThrowAnyException();
    }
}
```

**Verified:** this project has **no** `BadRequestException`. `ApiExceptionHandler:32` maps `IllegalArgumentException` to HTTP 400 (`ProblemDetail.forStatusAndDetail(BAD_REQUEST, ex.getMessage())`), so the validator throws `IllegalArgumentException` and the message reaches the client as the problem detail. Do not introduce a new exception type for this.

- [ ] **Step 2: Run to confirm it fails**

Run: `cd backend && mvn -q -Dtest=TemplateValidatorTest test`
Expected: FAIL — `cannot find symbol: class TemplateValidator`.

- [ ] **Step 3: Implement the validator**

Create `backend/src/main/java/ro/myfinance/settings/application/TemplateValidator.java`:

```java
package ro.myfinance.settings.application;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.TemplateRenderer;

/**
 * Validates a tenant's template before it is stored. The renderer is forgiving by design — it never
 * fails a send — so this is where a mistake has to surface, while the admin is still looking at the
 * editor. A typo'd {@code {{compnayName}}} that got through would reach a client as literal text.
 *
 * <p>A blank subject is legal: it means "keep the built-in subject", letting a firm reword the body only.
 */
@Component
public class TemplateValidator {

    private static final int MAX_BODY = 10_000;
    private static final int MAX_SUBJECT = 300;
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");

    public void validate(MessageTemplateKind kind, String subject, String body) {
        String normalizedBody = TemplateRenderer.normalize(body);
        String normalizedSubject = TemplateRenderer.normalize(subject);

        if (normalizedBody == null || normalizedBody.isBlank()) {
            throw new IllegalArgumentException("Template body must not be empty");
        }
        if (normalizedBody.length() > MAX_BODY) {
            throw new IllegalArgumentException(
                    "Template body must be at most " + MAX_BODY + " characters");
        }
        if (normalizedSubject != null && normalizedSubject.length() > MAX_SUBJECT) {
            throw new IllegalArgumentException(
                    "Template subject must be at most " + MAX_SUBJECT + " characters");
        }
        requireBalanced(normalizedBody);
        requireKnownNames(kind, normalizedBody, kind.allNames());
        if (normalizedSubject != null && !normalizedSubject.isBlank()) {
            requireBalanced(normalizedSubject);
            // Subjects take scalars only — a multi-line block in a subject makes no sense.
            Set<String> subjectNames = new LinkedHashSet<>(kind.placeholders());
            subjectNames.add(MessageTemplateKind.SIGN_OFF);
            requireKnownNames(kind, normalizedSubject, subjectNames);
        }
    }

    private static void requireKnownNames(MessageTemplateKind kind, String text, Set<String> allowed) {
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            if (!allowed.contains(m.group(1))) {
                throw new IllegalArgumentException(
                        "Unknown placeholder {{" + m.group(1) + "}} for " + kind
                                + ". Allowed: " + String.join(", ", new java.util.TreeSet<>(allowed)));
            }
        }
    }

    /** Every {@code {{} must be closed, and no stray {@code }}} may remain. */
    private static void requireBalanced(String text) {
        String withoutTokens = TOKEN.matcher(text).replaceAll("");
        if (withoutTokens.contains("{{") || withoutTokens.contains("}}")) {
            throw new IllegalArgumentException(
                    "Unbalanced or malformed placeholder delimiters — every {{name}} must be closed");
        }
    }
}
```

Replace `IllegalArgumentException` with the actual 400-mapped exception found in Step 1, and import it properly rather than fully qualifying.

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && mvn -q -Dtest=TemplateValidatorTest test`
Expected: PASS (9 tests).

- [ ] **Step 5: Add the DTOs**

Create `backend/src/main/java/ro/myfinance/settings/adapter/web/TemplateSettingsDtos.java`:

```java
package ro.myfinance.settings.adapter.web;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.MessageTemplateService;

/** Request/response shapes for the per-tenant message template editor. */
public final class TemplateSettingsDtos {

    private TemplateSettingsDtos() {
    }

    /**
     * One kind as the editor sees it. {@code customized} is false when the text shown is the built-in
     * default, so the UI can offer "Reset to default" only when there is something to reset.
     */
    public record TemplateResponse(String kind, String subject, String body, boolean customized,
                                   List<String> placeholders, List<String> blocks) {
        public static TemplateResponse from(MessageTemplateService.Effective e) {
            return new TemplateResponse(e.kind().name(), e.subject(), e.body(), e.customized(),
                    e.kind().placeholders().stream().sorted().toList(),
                    e.kind().blocks().stream().sorted().toList());
        }
    }

    public record UpdateTemplateRequest(String subject, @NotBlank String body) {
    }

    /** Preview the submitted, unsaved text against sample data — no company needed. */
    public record PreviewRequest(String subject, @NotBlank String body) {
    }

    public record PreviewResponse(String subject, String body, Map<String, String> sampleValues) {
    }
}
```

- [ ] **Step 6: Add the controller**

Create `backend/src/main/java/ro/myfinance/settings/adapter/web/TemplateSettingsController.java`:

```java
package ro.myfinance.settings.adapter.web;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.messaging.MessageTemplateService;
import ro.myfinance.common.messaging.SampleTemplateValues;
import ro.myfinance.common.messaging.TemplateRenderer;
import ro.myfinance.settings.adapter.web.TemplateSettingsDtos.PreviewRequest;
import ro.myfinance.settings.adapter.web.TemplateSettingsDtos.PreviewResponse;
import ro.myfinance.settings.adapter.web.TemplateSettingsDtos.TemplateResponse;
import ro.myfinance.settings.adapter.web.TemplateSettingsDtos.UpdateTemplateRequest;
import ro.myfinance.settings.application.TemplateValidator;

/**
 * Per-tenant message wording, managed by the firm admin. TENANT_ADMIN only, like the provider settings
 * next to it: wording goes to every client of the firm, so it is not a per-employee preference.
 */
@RestController
@RequestMapping("/api/v1/settings/messaging/templates")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class TemplateSettingsController {

    private final MessageTemplateService service;
    private final TemplateValidator validator;
    private final TemplateRenderer renderer;

    public TemplateSettingsController(MessageTemplateService service, TemplateValidator validator,
                                      TemplateRenderer renderer) {
        this.service = service;
        this.validator = validator;
        this.renderer = renderer;
    }

    /** Every kind, showing the tenant's text where customized and the built-in default otherwise. */
    @GetMapping
    public List<TemplateResponse> list() {
        return service.effectiveAll().stream().map(TemplateResponse::from).toList();
    }

    @PutMapping("/{kind}")
    public TemplateResponse update(@PathVariable MessageTemplateKind kind,
                                   @Valid @RequestBody UpdateTemplateRequest req) {
        validator.validate(kind, req.subject(), req.body());
        service.save(kind, req.subject(), req.body());
        return TemplateResponse.from(service.effective(kind));
    }

    /** Drop the override so this kind renders the built-in default again. */
    @DeleteMapping("/{kind}")
    public TemplateResponse reset(@PathVariable MessageTemplateKind kind) {
        service.reset(kind);
        return TemplateResponse.from(service.effective(kind));
    }

    /** Render the submitted (unsaved) text against sample data, so previewing needs no real company. */
    @PostMapping("/{kind}/preview")
    public PreviewResponse preview(@PathVariable MessageTemplateKind kind,
                                   @Valid @RequestBody PreviewRequest req) {
        validator.validate(kind, req.subject(), req.body());
        var values = SampleTemplateValues.forKind(kind);
        String subject = req.subject() == null || req.subject().isBlank()
                ? ro.myfinance.common.messaging.DefaultMessageTemplates.of(kind).subject()
                : req.subject();
        return new PreviewResponse(renderer.render(subject, values),
                renderer.render(req.body(), values), values);
    }
}
```

- [ ] **Step 7: Add the sample values used by preview**

Create `backend/src/main/java/ro/myfinance/common/messaging/SampleTemplateValues.java`:

```java
package ro.myfinance.common.messaging;

import java.util.HashMap;
import java.util.Map;

/**
 * Plausible stand-in values for the template preview, so a firm admin can see their wording rendered
 * without picking a real company — and without any real client data appearing in Settings.
 */
public final class SampleTemplateValues {

    private SampleTemplateValues() {
    }

    public static Map<String, String> forKind(MessageTemplateKind kind) {
        Map<String, String> v = new HashMap<>();
        v.put("companyName", "Exemplu SRL");
        v.put("cui", "RO12345678");
        v.put("month", "Aprilie 2026");
        v.put("treasury", "Trezoreria Cluj Napoca");
        v.put("totalToPay", "1550");
        v.put("revenue", "100 000.00");
        v.put("expenses", "61 000.00");
        v.put("grossProfit", "40 000.00");
        v.put("incomeTax", "1 000.00");
        v.put("netProfit", "39 000.00");
        v.put("taxDeadline", "25 Aprilie 2026");
        v.put("missingCount", "2");
        v.put("signOff", "Maria Popescu\nContaZone SRL");
        v.put("paymentSummary", "TVA: 1250\nImpozite: 300");
        v.put("paymentAccounts", String.join("\n",
                "",
                "- 1250 lei in contul RO11TREZ1112345678901234",
                "- Beneficiar: Trezoreria Cluj Napoca",
                "- CUI Beneficiar: RO12345678",
                "- Explicație: TVA Aprilie-2026",
                "- Scadență: 25 aprilie 2026"));
        v.put("missingTransactions", String.join("\n",
                "• 15.04.2026 — Furnizor Alfa SRL — 1.234,56 RON",
                "• 22.04.2026 — Furnizor Beta SRL — 310,00 RON"));
        return v;
    }
}
```

- [ ] **Step 8: Write the authorization IT**

Create `backend/src/test/java/ro/myfinance/settings/TemplateSettingsAuthzIT.java`, following the same `@SpringBootTest` + `MockMvc` + JWT-stubbing pattern as the nearest existing settings IT:

```java
    @Test
    void tenantAdminCanListEveryKind() throws Exception {
        mvc.perform(get("/api/v1/settings/messaging/templates").with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[?(@.kind == 'TAX')].customized").value(org.hamcrest.Matchers.hasItem(false)));
    }

    @Test
    void employeeCannotRead() throws Exception {
        mvc.perform(get("/api/v1/settings/messaging/templates").with(employeeAuth()))
                .andExpect(status().isForbidden());
    }

    @Test
    void representativeCannotRead() throws Exception {
        mvc.perform(get("/api/v1/settings/messaging/templates").with(repAuth()))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCannotWrite() throws Exception {
        mvc.perform(put("/api/v1/settings/messaging/templates/{kind}", "PAYROLL")
                        .with(employeeAuth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"X\",\"body\":\"Bună ziua\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCannotReset() throws Exception {
        mvc.perform(delete("/api/v1/settings/messaging/templates/{kind}", "PAYROLL").with(employeeAuth()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownPlaceholderIsRejectedWithFourHundred() throws Exception {
        mvc.perform(put("/api/v1/settings/messaging/templates/{kind}", "PAYROLL")
                        .with(adminAuth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"X\",\"body\":\"Bună ziua {{compnayName}}\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownKindIsRejectedWithFourHundred() throws Exception {
        mvc.perform(get("/api/v1/settings/messaging/templates").with(adminAuth()))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/settings/messaging/templates/{kind}", "NOT_A_KIND").with(adminAuth()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void saveThenResetRoundTripsBackToTheDefault() throws Exception {
        mvc.perform(put("/api/v1/settings/messaging/templates/{kind}", "PAYROLL")
                        .with(adminAuth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Salarii {{month}}\",\"body\":\"Al nostru {{month}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customized").value(true))
                .andExpect(jsonPath("$.body").value("Al nostru {{month}}"));

        mvc.perform(delete("/api/v1/settings/messaging/templates/{kind}", "PAYROLL").with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customized").value(false))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("fluturașul")));
    }

    @Test
    void previewRendersSampleData() throws Exception {
        mvc.perform(post("/api/v1/settings/messaging/templates/{kind}/preview", "REMINDER_DOCS_MISSING")
                        .with(adminAuth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"S {{month}}\",\"body\":\"Lista:\\n{{missingTransactions}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("S Aprilie 2026"))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("Furnizor Alfa SRL")));
    }
```

- [ ] **Step 9: Run the IT**

Run: `cd backend && mvn -q -Dtest=TemplateSettingsAuthzIT verify`
Expected: PASS (9 tests). If the unknown-kind case returns 500 rather than 400, add an `@ExceptionHandler(MethodArgumentTypeMismatchException.class)` to the project's global handler, or accept 400 only after confirming Spring's default enum-conversion failure maps there.

- [ ] **Step 10: Full backend verification and commit**

Run: `cd backend && mvn -q verify`
Expected: PASS.

```bash
git add backend/src/main/java/ro/myfinance/settings/ \
        backend/src/main/java/ro/myfinance/common/messaging/SampleTemplateValues.java \
        backend/src/test/java/ro/myfinance/messaging/TemplateValidatorTest.java \
        backend/src/test/java/ro/myfinance/settings/TemplateSettingsAuthzIT.java
git commit -m "feat(settings): add TENANT_ADMIN message template endpoints

List, update, reset and preview, with save-time validation that rejects
unknown placeholders, blocks in subjects and malformed delimiters — so a
typo fails in Settings instead of reaching a client as literal text."
```

---

## Task 11: Template editor in Settings

**Files:**
- Create: `frontend/src/api/templates.ts`
- Create: `frontend/src/components/MessageTemplatesSection.tsx`
- Modify: `frontend/src/pages/DataSources.tsx:183`
- Modify: `frontend/src/i18n.ts`

**Interfaces:**
- Consumes: the Task 10 endpoints.
- Produces: `templatesApi` (`list`, `update`, `reset`, `preview`), and the `MessageTemplatesSection` component.

- [ ] **Step 1: Add the API client**

Create `frontend/src/api/templates.ts`:

```ts
import { api } from "../lib/apiClient";

/**
 * Per-tenant message wording. `customized` is false when the text returned is the built-in default,
 * so the UI offers "Reset to default" only when there is an override to drop.
 */

export type TemplateKind =
  | "TAX"
  | "REPORT"
  | "PAYROLL"
  | "REMINDER_NOTHING_RECEIVED"
  | "REMINDER_DOCS_MISSING"
  | "REMINDER_DOCS_ONLY";

export interface MessageTemplate {
  kind: TemplateKind;
  subject: string;
  body: string;
  customized: boolean;
  /** Scalar names this kind's template may use, e.g. "companyName". */
  placeholders: string[];
  /** Multi-line lists the server renders, e.g. "missingTransactions". */
  blocks: string[];
}

export interface UpdateTemplateInput {
  /** Blank keeps the built-in subject. */
  subject: string;
  body: string;
}

export interface TemplatePreview {
  subject: string;
  body: string;
  sampleValues: Record<string, string>;
}

const base = "/api/v1/settings/messaging/templates";

export const templatesApi = {
  list: () => api<MessageTemplate[]>(base),
  update: (kind: TemplateKind, input: UpdateTemplateInput) =>
    api<MessageTemplate>(`${base}/${kind}`, { method: "PUT", body: JSON.stringify(input) }),
  reset: (kind: TemplateKind) => api<MessageTemplate>(`${base}/${kind}`, { method: "DELETE" }),
  preview: (kind: TemplateKind, input: UpdateTemplateInput) =>
    api<TemplatePreview>(`${base}/${kind}/preview`, { method: "POST", body: JSON.stringify(input) }),
};
```

- [ ] **Step 2: Add the i18n keys**

In `frontend/src/i18n.ts`, add to the Romanian block (near the existing `settings.msg.*` keys) and the mirrored English block:

```ts
      // Romanian
      "templates.title": "Textul mesajelor",
      "templates.intro": "Textul implicit al mesajelor trimise clienților. Modificările se aplică tuturor trimiterilor viitoare; fiecare mesaj rămâne editabil înainte de trimitere.",
      "templates.kind.TAX": "Sume de plată la stat",
      "templates.kind.REPORT": "Raport financiar lunar",
      "templates.kind.PAYROLL": "State de plată",
      "templates.kind.REMINDER_NOTHING_RECEIVED": "Reminder — nu am primit nimic",
      "templates.kind.REMINDER_DOCS_MISSING": "Reminder — lipsesc documente anume",
      "templates.kind.REMINDER_DOCS_ONLY": "Reminder — doar facturi / chitanțe",
      "templates.subject": "Subiect",
      "templates.subjectHint": "Lăsați gol pentru subiectul implicit.",
      "templates.body": "Mesaj",
      "templates.placeholders": "Câmpuri disponibile",
      "templates.blocks": "Liste generate automat",
      "templates.blocksHint": "Aceste liste sunt completate de aplicație — le puteți muta, dar nu le puteți formata.",
      "templates.preview": "Previzualizare",
      "templates.previewNote": "Previzualizare cu date de exemplu.",
      "templates.reset": "Revino la textul implicit",
      "templates.customized": "Personalizat",
      "templates.default": "Implicit",
      "templates.saved": "Text salvat",
```

```ts
      // English
      "templates.title": "Message wording",
      "templates.intro": "The default wording of the messages sent to your clients. Changes apply to future sends; every message stays editable before sending.",
      "templates.kind.TAX": "State payments",
      "templates.kind.REPORT": "Monthly financial report",
      "templates.kind.PAYROLL": "Payroll",
      "templates.kind.REMINDER_NOTHING_RECEIVED": "Reminder — nothing received yet",
      "templates.kind.REMINDER_DOCS_MISSING": "Reminder — specific documents missing",
      "templates.kind.REMINDER_DOCS_ONLY": "Reminder — invoices / receipts only",
      "templates.subject": "Subject",
      "templates.subjectHint": "Leave empty for the built-in subject.",
      "templates.body": "Message",
      "templates.placeholders": "Available fields",
      "templates.blocks": "Auto-generated lists",
      "templates.blocksHint": "These lists are filled in by the app — you can move them, but not format them.",
      "templates.preview": "Preview",
      "templates.previewNote": "Preview uses sample data.",
      "templates.reset": "Reset to default wording",
      "templates.customized": "Customized",
      "templates.default": "Default",
      "templates.saved": "Wording saved",
```

- [ ] **Step 3: Build the editor component**

Create `frontend/src/components/MessageTemplatesSection.tsx`:

```tsx
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { templatesApi, type MessageTemplate, type TemplateKind } from "../api/templates";
import { ApiError } from "../lib/apiClient";
import { Field } from "./Field";

const errStyle: React.CSSProperties = { color: "#dc2626", marginTop: 8 };
const okStyle: React.CSSProperties = { color: "#059669", marginTop: 8 };
const hint: React.CSSProperties = { color: "var(--text-muted)", fontSize: 13, marginTop: 0 };
const chip: React.CSSProperties = {
  display: "inline-block", padding: "1px 6px", borderRadius: 4, fontSize: 12,
  background: "var(--th-bg)", marginRight: 6, marginBottom: 4, fontFamily: "monospace",
};
const pre: React.CSSProperties = {
  whiteSpace: "pre-wrap", background: "var(--th-bg)", padding: 10, borderRadius: 6,
  fontSize: 13, margin: 0,
};

/**
 * Per-tenant message wording. The firm admin edits each message once here; every send then pre-fills
 * from this text and stays editable in its own modal. Placeholders like {{month}} are substituted by the
 * server — an unknown one is rejected on save rather than mailed to a client as literal text.
 */
export function MessageTemplatesSection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({ queryKey: ["templates"], queryFn: templatesApi.list });

  const [kind, setKind] = useState<TemplateKind>("TAX");
  // undefined = not edited yet, so the server's text shows through.
  const [draft, setDraft] = useState<{ subject?: string; body?: string }>({});
  const [msg, setMsg] = useState<{ ok?: string; err?: string }>({});
  const [preview, setPreview] = useState<string | null>(null);

  const current: MessageTemplate | undefined = data?.find((x) => x.kind === kind);
  const subject = draft.subject ?? current?.subject ?? "";
  const body = draft.body ?? current?.body ?? "";

  const pick = (next: TemplateKind) => {
    setKind(next);
    setDraft({});
    setMsg({});
    setPreview(null);
  };

  const invalidate = () => qc.invalidateQueries({ queryKey: ["templates"] });
  const fail = (e: unknown) => setMsg({ err: e instanceof ApiError ? e.message : "Failed" });

  const save = useMutation({
    mutationFn: () => templatesApi.update(kind, { subject, body }),
    onSuccess: () => { void invalidate(); setDraft({}); setMsg({ ok: t("templates.saved") }); },
    onError: fail,
  });

  const reset = useMutation({
    mutationFn: () => templatesApi.reset(kind),
    onSuccess: () => { void invalidate(); setDraft({}); setPreview(null); setMsg({ ok: t("templates.saved") }); },
    onError: fail,
  });

  const runPreview = useMutation({
    mutationFn: () => templatesApi.preview(kind, { subject, body }),
    onSuccess: (p) => { setPreview(`${p.subject}\n\n${p.body}`); setMsg({}); },
    onError: fail,
  });

  if (isLoading) return <div className="card">…</div>;

  return (
    <div className="card" style={{ display: "grid", gap: 12 }}>
      <div>
        <h3 style={{ margin: 0 }}>{t("templates.title")}</h3>
        <p style={hint}>{t("templates.intro")}</p>
      </div>

      <Field label={t("templates.title")}>
        <select value={kind} onChange={(e) => pick(e.target.value as TemplateKind)}>
          {(data ?? []).map((x) => (
            <option key={x.kind} value={x.kind}>
              {t(`templates.kind.${x.kind}`)} — {x.customized ? t("templates.customized") : t("templates.default")}
            </option>
          ))}
        </select>
      </Field>

      <Field label={t("templates.subject")}>
        <input value={subject} onChange={(e) => setDraft((d) => ({ ...d, subject: e.target.value }))} />
        <p style={hint}>{t("templates.subjectHint")}</p>
      </Field>

      <Field label={t("templates.body")}>
        <textarea rows={16} value={body} style={{ fontFamily: "monospace", fontSize: 13 }}
          onChange={(e) => setDraft((d) => ({ ...d, body: e.target.value }))} />
      </Field>

      <div>
        <div style={{ fontSize: 13, marginBottom: 4 }}>{t("templates.placeholders")}</div>
        <div>
          {[...(current?.placeholders ?? []), "signOff"].map((p) => (
            <code key={p} style={chip}>{`{{${p}}}`}</code>
          ))}
        </div>
        {(current?.blocks?.length ?? 0) > 0 && (
          <>
            <div style={{ fontSize: 13, margin: "8px 0 4px" }}>{t("templates.blocks")}</div>
            <div>
              {current?.blocks.map((b) => <code key={b} style={chip}>{`{{${b}}}`}</code>)}
            </div>
            <p style={hint}>{t("templates.blocksHint")}</p>
          </>
        )}
      </div>

      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        <button onClick={() => save.mutate()} disabled={save.isPending || !body.trim()}>
          {t("common.save")}
        </button>
        <button onClick={() => runPreview.mutate()} disabled={runPreview.isPending || !body.trim()}>
          {t("templates.preview")}
        </button>
        {current?.customized && (
          <button onClick={() => reset.mutate()} disabled={reset.isPending}>
            {t("templates.reset")}
          </button>
        )}
      </div>

      {preview !== null && (
        <div>
          <p style={hint}>{t("templates.previewNote")}</p>
          <pre style={pre}>{preview}</pre>
        </div>
      )}

      {msg.err && <div style={errStyle}>{msg.err}</div>}
      {msg.ok && <div style={okStyle}>{msg.ok}</div>}
    </div>
  );
}
```

Match `Field`'s actual prop name by reading `frontend/src/components/Field.tsx` first, and reuse whatever `common.save` key already exists.

- [ ] **Step 4: Mount it in Settings**

In `frontend/src/pages/DataSources.tsx`, add the import beside the existing one and render it under the two provider sections:

```tsx
import { MessageTemplatesSection } from "../components/MessageTemplatesSection";
```

```tsx
      <EmailProviderSection />
      <WhatsAppProviderSection />
      <MessageTemplatesSection />
```

- [ ] **Step 5: Typecheck and build**

Run: `cd frontend && npx tsc --noEmit && npm run build`
Expected: both clean. If the build fails on Node 24 with a Workbox/Terser error, use Node 20 or 22 LTS (`nvm use 20`) — a known upstream issue in this repo, unrelated to this change.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/templates.ts frontend/src/components/MessageTemplatesSection.tsx \
        frontend/src/pages/DataSources.tsx frontend/src/i18n.ts
git commit -m "feat(settings): add the message wording editor

Kind picker, subject and body editors, the placeholder reference for the
selected kind, sample-data preview and reset-to-default, alongside the
existing email and WhatsApp provider sections."
```

---

## Task 12: Switch the reminder screens to the server body

**Files:**
- Modify: `frontend/src/api/documents.ts`
- Modify: `frontend/src/components/SendReminderModal.tsx`
- Modify: `frontend/src/pages/Statements.tsx:132`
- Modify: `frontend/src/pages/ReconcileWorkspace.tsx:296`
- Delete: `frontend/src/lib/reminderBody.ts`
- Modify: `frontend/src/i18n.ts`

**Interfaces:**
- Consumes: `GET /api/v1/companies/{companyId}/document-reminders/body?period=` (Task 9).
- Produces: `remindersApi.body(companyId, period) -> Promise<{state, subject, body}>`.

- [ ] **Step 1: Add the API call**

In `frontend/src/api/documents.ts`, extend `remindersApi`:

```ts
export interface ReminderBody {
  /** Which of the three states the server chose for this company + period. */
  state: "REMINDER_NOTHING_RECEIVED" | "REMINDER_DOCS_MISSING" | "REMINDER_DOCS_ONLY";
  subject: string;
  body: string;
}
```

```ts
  body: (companyId: string, period: string) =>
    api<ReminderBody>(`/api/v1/companies/${companyId}/document-reminders/body?period=${period}`),
```

- [ ] **Step 2: Rewrite `SendReminderModal` to fetch each body**

Replace the `txnQueries`/`bodyFor` machinery. Delete the `reminderBody` import, the `bankApi` import if now unused, the `useFirmName`/`fromName` plumbing used only for the body, and the `useQueries` block. Then:

```tsx
  // The server decides which of the three reminder states applies and renders this tenant's wording,
  // so the email and the WhatsApp message always read identically. Previously the browser fetched every
  // company's transactions just to work this out itself.
  useEffect(() => {
    let cancelled = false;
    Promise.all(companies.map((c) =>
      remindersApi.body(c.id, period).then((r) => ({ id: c.id, body: r.body }))
        .catch(() => ({ id: c.id, body: "" })),
    )).then((results) => {
      if (cancelled) return;
      setDrafts((d) => {
        const next = { ...d };
        for (const r of results) {
          const cur = next[r.id];
          if (!cur || !cur.loading) continue; // never clobber an edit in progress
          next[r.id] = { ...cur, body: r.body, loading: false };
        }
        return next;
      });
    });
    return () => { cancelled = true; };
  }, [companies, period]);
```

Keep the existing envelope effect that pre-fills each recipient — it is unrelated. `ReminderTarget.hasBankStatement` / `hasInvoiceOrReceipt` are no longer read for the body; leave the interface alone if the Statements list still uses it for its own columns, otherwise drop the now-unused fields.

- [ ] **Step 3: Rewrite the two WhatsApp body loaders**

`frontend/src/pages/Statements.tsx` — replace `waBody`:

```tsx
  const waBody = async (id: string) => (await remindersApi.body(id, period)).body;
```

`frontend/src/pages/ReconcileWorkspace.tsx` — replace `waLoadBody`:

```tsx
  // WhatsApp document-reminder: the same server-rendered body as the email, so both channels read
  // identically. Click-to-chat opens wa.me directly; Twilio mode opens the compose modal.
  const waLoadBody = async (): Promise<string> => (await remindersApi.body(companyId, period)).body;
```

Remove the now-unused `reminderBody`, `emailApi.envelope`, `useFirmName` and `t` references from both bodies if nothing else in the file needs them — let the TypeScript build tell you.

- [ ] **Step 4: Delete the old builder and its dead keys**

```bash
git rm frontend/src/lib/reminderBody.ts
```

In `frontend/src/i18n.ts`, delete these from **both** the RO and EN blocks — verified to be used only by the deleted file, except the last two which were already unused:

`email.greeting`, `email.needStatementAndDocs`, `email.needDocsForTxns`, `email.uploadPortal`, `email.signoff`, `email.needDocs`, `email.subject`

Keep `email.subjectLabel`, `email.reminderTitle`, `email.previewIntro` and every other `email.*` UI label — they label controls, not message content.

- [ ] **Step 5: Confirm nothing still references the removed keys**

Run:
```bash
cd frontend && grep -rn "reminderBody\|email\.greeting\|email\.needStatementAndDocs\|email\.needDocsForTxns\|email\.uploadPortal\|email\.signoff\|email\.needDocs\|\"email\.subject\"" src/
```
Expected: no output.

- [ ] **Step 6: Typecheck and build**

Run: `cd frontend && npx tsc --noEmit && npm run build`
Expected: both clean. Unused-import errors here are the signal from Step 3 — remove what the compiler names.

- [ ] **Step 7: Commit**

```bash
git add -A frontend/src
git commit -m "refactor(frontend): take the reminder body from the server

Deletes reminderBody.ts and its i18n keys; the three screens that used
it now fetch the rendered body. Drops the per-company transaction
queries the modal only made to work out the state itself."
```

---

## Task 13: Rendering isolation and whole-feature verification

Task 1 proved the database refuses cross-tenant access. This proves the *rendering path* honours it: with tenant A bound, a send renders A's wording, and B's override is invisible. That is the failure that would actually leak — one firm's client receiving another firm's text.

**Files:**
- Test: `backend/src/test/java/ro/myfinance/messaging/TemplateRenderingIsolationIT.java`

**Interfaces:**
- Consumes: `MessageTemplateService`, `MessageTemplateRepository`, `TenantContext`.
- Produces: nothing — verification only.

- [ ] **Step 1: Write the failing isolation IT**

Create `backend/src/test/java/ro/myfinance/messaging/TemplateRenderingIsolationIT.java` as a `@SpringBootTest` against the same Testcontainers Postgres the other `*IT` classes use (copy the class-level setup from the nearest existing `*IT`):

```java
    @Test
    void eachTenantRendersItsOwnWording() {
        asTenant(TENANT_A, () -> service.save(MessageTemplateKind.PAYROLL, "A subiect {{month}}",
                "Textul firmei A pentru {{month}}."));
        asTenant(TENANT_B, () -> service.save(MessageTemplateKind.PAYROLL, "B subiect {{month}}",
                "Textul firmei B pentru {{month}}."));

        var a = asTenant(TENANT_A, () -> service.render(MessageTemplateKind.PAYROLL,
                Map.of("month", "Aprilie 2026")));
        var b = asTenant(TENANT_B, () -> service.render(MessageTemplateKind.PAYROLL,
                Map.of("month", "Aprilie 2026")));

        assertThat(a.body()).isEqualTo("Textul firmei A pentru Aprilie 2026.");
        assertThat(a.subject()).isEqualTo("A subiect Aprilie 2026");
        assertThat(b.body()).isEqualTo("Textul firmei B pentru Aprilie 2026.");
        assertThat(b.subject()).isEqualTo("B subiect Aprilie 2026");
    }

    @Test
    void aTenantWithNoOverrideStillGetsTheDefaultNotAnotherTenantsText() {
        asTenant(TENANT_A, () -> service.save(MessageTemplateKind.REPORT, "A", "Doar A."));
        var b = asTenant(TENANT_B, () -> service.effective(MessageTemplateKind.REPORT));
        assertThat(b.customized()).isFalse();
        assertThat(b.body()).contains("Vă transmitem atașat raportul financiar");
        assertThat(b.body()).doesNotContain("Doar A.");
    }

    @Test
    void effectiveAllNeverLeaksAnotherTenantsOverride() {
        asTenant(TENANT_A, () -> service.save(MessageTemplateKind.TAX, "A", "Secretul firmei A"));
        var all = asTenant(TENANT_B, () -> service.effectiveAll());
        assertThat(all).hasSize(MessageTemplateKind.values().length);
        assertThat(all).noneMatch(e -> e.body().contains("Secretul firmei A"));
        assertThat(all).allMatch(e -> !e.customized());
    }

    @Test
    void resetOnlyAffectsTheBoundTenant() {
        asTenant(TENANT_A, () -> service.save(MessageTemplateKind.PAYROLL, "A", "Textul A"));
        asTenant(TENANT_B, () -> service.save(MessageTemplateKind.PAYROLL, "B", "Textul B"));
        asTenant(TENANT_B, () -> { service.reset(MessageTemplateKind.PAYROLL); return null; });

        assertThat(asTenant(TENANT_A, () -> service.effective(MessageTemplateKind.PAYROLL)).body())
                .isEqualTo("Textul A");
        assertThat(asTenant(TENANT_B, () -> service.effective(MessageTemplateKind.PAYROLL)).customized())
                .isFalse();
    }
```

`asTenant(UUID, Supplier<T>)` binds `TenantContext.set(new TenantContext.Identity(tenantId, userId, Role.TENANT_ADMIN, null))`, runs the supplier, then clears in a `finally`. Seed both tenant rows in `@BeforeAll` the way the other ITs do.

- [ ] **Step 2: Run it**

Run: `cd backend && mvn -q -Dtest=TemplateRenderingIsolationIT verify`
Expected: PASS (4 tests).

- [ ] **Step 3: Run the complete backend suite**

Run: `cd backend && mvn -q verify`
Expected: PASS, including every `*IT`. Confirm these are green by name, since they are the feature's safety net:
`CurrentMessageWordingTest`, `DefaultMessageTemplatesTest`, `MessageTemplateIsolationTest`, `TemplateRenderingIsolationIT`, `TemplateSettingsAuthzIT`, `ReminderStateSelectionTest`.

- [ ] **Step 4: Run the complete frontend build**

Run: `cd frontend && npx tsc --noEmit && npm run build`
Expected: both clean.

- [ ] **Step 5: Manual smoke test**

Start the app (`backend/run-local.sh` — confirm it exports `MYFINANCE_SECRET_KEY`, which an older copy of that script is missing) and, as a TENANT_ADMIN:

1. Settings → **Message wording**: every one of the six kinds shows text and reads *Default*.
2. Edit **Payroll**, click **Preview** — sample figures appear, no raw `{{…}}` remains.
3. Type `{{compnayName}}` and **Save** — a 400 with the placeholder named, not a saved template.
4. Save a real change → the picker now reads *Customized*; open a payroll email → the modal pre-fills your wording.
5. **Reset to default** → the built-in Romanian text returns.
6. Statements → send a reminder for a company with no bank statement; confirm the generic wording and a subject reading `Documente lipsă — Aprilie 2026`.
7. Reconcile a company whose statement is in and whose documents are all matched; confirm the reminder uses the *invoices only* wording rather than asking for the statement again.

- [ ] **Step 6: Commit and open the PR**

```bash
git add backend/src/test/java/ro/myfinance/messaging/TemplateRenderingIsolationIT.java
git commit -m "test(messaging): prove the render path is tenant-isolated

Each tenant renders its own wording; a tenant with no override gets the
built-in default rather than another firm's text, and reset affects only
the bound tenant."
git push -u origin feat/tenant-message-templates
gh pr create --repo dmarius23/MyFinance --fill
```

Note from prior sessions: `gh` needs `--repo dmarius23/MyFinance` explicitly in this checkout, and this iCloud-synced working tree can wedge git — if a command hangs for minutes, `pkill -f "git -c core.quotepath=false.*numstat"` then `rm -f .git/index.lock`.

---

## Self-Review

**1. Spec coverage**

| Spec section | Task |
|---|---|
| §3 placeholder contract | 2 |
| §3 `{{signOff}}` narrowing | 4 |
| §4 data model + RLS | 1 |
| §5 renderer + its three rules | 2 |
| §6 six default templates | 4 |
| §7 builder refactor | 6, 7, 8 |
| §8 API + validation | 10 |
| §9 reminder consolidation | 9 (backend), 12 (frontend) |
| §10 testing, incl. mandatory isolation | 1, 13 (isolation), 3–4 (golden), 10 (authz), 9 (states) |
| §11 deliberate changes | 9 Step 10 (month format), 9 Step 3 (row format), 12 (language) |
| §12 out of scope | respected — `docRequestBody` and `InviteMessageFactory` untouched |

No gaps. The settings UI (§8's editor) is Task 11, which the spec covers under "UI".

**2. Placeholders** — none. Every code step carries real code. Four assumptions were checked against the codebase while reviewing, and the plan now states the verified answer instead of asking the implementer to look it up:

- There is **no** `BadRequestException`; `ApiExceptionHandler:32` maps `IllegalArgumentException` to 400.
- `Role` is `{SUPER_ADMIN, TENANT_ADMIN, EMPLOYEE, REPRESENTATIVE}`; `TenantContext.Identity` takes four components.
- `EmailEnvelopeService` has **no** `companyName(UUID)` — `ReminderBodyService` injects `ro.myfinance.company.adapter.persistence.CompanyRepository` and reads `Company.getLegalName()` (both verified to exist).
- The latest migration is `V61__invoice_period_mode.sql`, so `V62` is free.

The remaining lookups (`TxnWithMatches` shape, `BankTransaction` accessors, `DocumentType.BANK_STATEMENT`, `Field`'s prop name, the nearest existing `*IT` to copy) are deliberate: they are shapes a test must mirror exactly, and naming them here without reading them would invite a confidently wrong signature.

**3. Type consistency** — `MessageTemplateKind` is created minimally in Task 1 Step 3 and completed in Task 2 Step 3, which is called out in Task 1's Interfaces note. `Computation` gains `subject` as its last component in Task 6 and nothing earlier depends on its arity. `composeBody(UUID, LocalDate) -> String` keeps its signature in Tasks 7 and 8, so `ReportController` and `PayrollController` are untouched. `TemplateRenderer.normalize` is static and used by both the renderer (Task 2) and the validator (Task 10). `RenderedMessage` (Task 5) is the return type used in Tasks 6–9.

**4. Review Focus coverage** — all five have an owning task and a step: data containing `{{…}}` (2.9), CRLF (2.11 render, 10.1 save), omitted block (2.13), stale placeholder (2.7), 200-row block (9.11).

One residual risk worth stating rather than hiding: **Task 4 Step 8 and Task 3 Steps 4/6 may need whitespace iteration**, because block strings carry their own line breaks and the exact blank lines cannot be fully determined without running the code. Both steps say explicitly that the pinned expectation wins and the template constant is what moves. That is the correct direction — the pinned text is what clients receive today.

