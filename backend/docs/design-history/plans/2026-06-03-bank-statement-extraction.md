# Bank Statement Extraction + Statements Screen (Slice B) Implementation Plan

> **For agentic workers:** subagent-driven-development. Steps use `- [ ]` checkboxes.

**Goal:** Parse bank-statement PDFs into transactions (sync on upload), and rebuild the Statements & invoices screen to follow the prototype (monthly company list, files modal + viewer, Bank-transactions modal).

**Architecture:** New `ro.myfinance.extraction` module with a `BankStatementParser` port + registry. Intake publishes `DocumentUploadedEvent`; an extraction `@EventListener` parses `BANK_STATEMENT` docs synchronously, persists `bank_statement` + `bank_transaction` (Flyway `V5`), with per-statement cross-check. Read endpoints + a documents-summary feed the rebuilt React screen.

**Tech Stack:** Java 21 / Spring Boot 3.5.9 / JPA / Flyway / PDFBox / Testcontainers; React 18 / TS / Vite / TanStack Query / react-i18next.

---

## Context for the implementer
- RLS: every table has `tenant_id`; `TenantContext.set(...)` before DB ops in tests, `clear()` in `@AfterEach`. Patterns: `DocumentServiceIT`, `SettingsServiceIT`.
- Module layout: `ro.myfinance.<module>/{domain,application,adapter/{persistence,web,external}}`.
- Errors: `NotFoundException`(404)/`ConflictException`(409)/`IllegalArgumentException`(400) in/handled by `ro.myfinance.common.web`.
- Audit: `AuditRecorder.record(action, entity, id)`.
- `TenantContext`: `current()`, `tenantId()`, `companyId()` (Optional), `Identity(tenantId,userId,role,companyId)`.
- Docker NOT installed → `*IT` skip locally. Verify: `mvn -B -DskipTests test-compile` and `mvn -B test` (unit pass, ITs skip). Frontend: `npm run lint && npm run build`.
- Flyway local has `out-of-order: true`, so `V5` applies on the dev DB.
- Existing intake: `ro.myfinance.intake` — `DocumentService.upload(companyId, periodMonth, filename, contentType, bytes)`, `Document` entity with `getType()` (`DocumentType` enum incl. `BANK_STATEMENT`), `DocumentRepository`.

---

## Task 1: V5 migration

**File:** Create `backend/src/main/resources/db/migration/V5__bank_statement.sql`

- [ ] **Step 1:** Write:
```sql
CREATE TABLE bank_statement (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenant(id),
    document_id     uuid NOT NULL UNIQUE REFERENCES document(id) ON DELETE CASCADE,
    company_id      uuid NOT NULL REFERENCES company(id),
    period_month    date NOT NULL,
    bank_code       text,
    account_iban    text,
    opening_balance numeric(15,2),
    closing_balance numeric(15,2),
    status          text NOT NULL,
    cross_check_ok  boolean NOT NULL DEFAULT false,
    txn_count       int NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE bank_transaction (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenant(id),
    company_id          uuid NOT NULL REFERENCES company(id),
    statement_id        uuid NOT NULL REFERENCES bank_statement(id) ON DELETE CASCADE,
    txn_date            date NOT NULL,
    amount              numeric(15,2) NOT NULL,
    direction           text NOT NULL,
    partner_name        text,
    partner_iban        text,
    description         text,
    ref                 text,
    balance_after       numeric(15,2),
    matched_document_id uuid REFERENCES document(id),
    requires_document   boolean NOT NULL DEFAULT false,
    decision_source     text,
    category            text,
    override_reason     text
);
CREATE INDEX idx_bank_txn_company ON bank_transaction(tenant_id, company_id);
CREATE INDEX idx_bank_txn_statement ON bank_transaction(statement_id);

ALTER TABLE bank_statement ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_statement FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bank_statement
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE bank_transaction ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_transaction FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bank_transaction
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON bank_statement TO myfinance_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON bank_transaction TO myfinance_app;
    END IF;
END $$;
```
- [ ] **Step 2:** `cd backend && mvn -B -DskipTests test-compile` → BUILD SUCCESS.
- [ ] **Step 3:** Commit: `git add backend/src/main/resources/db/migration/V5__bank_statement.sql && git commit -m "feat(extraction): V5 bank_statement + bank_transaction tables"`

---

## Task 2: Extraction domain + repositories

**Files:** Create under `backend/src/main/java/ro/myfinance/extraction/`.

- [ ] **Step 1:** `domain/TxnDirection.java`
```java
package ro.myfinance.extraction.domain;

public enum TxnDirection {
    DEBIT, CREDIT
}
```
- [ ] **Step 2:** `domain/StatementStatus.java`
```java
package ro.myfinance.extraction.domain;

public enum StatementStatus {
    EXTRACTED, NEEDS_REVIEW, FAILED
}
```
- [ ] **Step 3:** `domain/BankStatement.java`
```java
package ro.myfinance.extraction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "bank_statement")
public class BankStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    @Column(name = "bank_code")
    private String bankCode;

    @Column(name = "account_iban")
    private String accountIban;

    @Column(name = "opening_balance")
    private BigDecimal openingBalance;

    @Column(name = "closing_balance")
    private BigDecimal closingBalance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatementStatus status;

    @Column(name = "cross_check_ok", nullable = false)
    private boolean crossCheckOk;

    @Column(name = "txn_count", nullable = false)
    private int txnCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BankStatement() {
    }

    public BankStatement(UUID tenantId, UUID documentId, UUID companyId, LocalDate periodMonth,
                         String bankCode, String accountIban, BigDecimal openingBalance,
                         BigDecimal closingBalance, StatementStatus status, boolean crossCheckOk,
                         int txnCount) {
        this.tenantId = tenantId;
        this.documentId = documentId;
        this.companyId = companyId;
        this.periodMonth = periodMonth;
        this.bankCode = bankCode;
        this.accountIban = accountIban;
        this.openingBalance = openingBalance;
        this.closingBalance = closingBalance;
        this.status = status;
        this.crossCheckOk = crossCheckOk;
        this.txnCount = txnCount;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public LocalDate getPeriodMonth() { return periodMonth; }
    public String getBankCode() { return bankCode; }
    public String getAccountIban() { return accountIban; }
    public BigDecimal getOpeningBalance() { return openingBalance; }
    public BigDecimal getClosingBalance() { return closingBalance; }
    public StatementStatus getStatus() { return status; }
    public boolean isCrossCheckOk() { return crossCheckOk; }
    public int getTxnCount() { return txnCount; }
}
```
- [ ] **Step 4:** `domain/BankTransaction.java`
```java
package ro.myfinance.extraction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "bank_transaction")
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "statement_id", nullable = false, updatable = false)
    private UUID statementId;

    @Column(name = "txn_date", nullable = false)
    private LocalDate txnDate;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TxnDirection direction;

    @Column(name = "partner_name")
    private String partnerName;

    @Column(name = "partner_iban")
    private String partnerIban;

    private String description;

    private String ref;

    @Column(name = "balance_after")
    private BigDecimal balanceAfter;

    protected BankTransaction() {
    }

    public BankTransaction(UUID tenantId, UUID companyId, UUID statementId, LocalDate txnDate,
                           BigDecimal amount, TxnDirection direction, String partnerName,
                           String partnerIban, String description, String ref, BigDecimal balanceAfter) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.statementId = statementId;
        this.txnDate = txnDate;
        this.amount = amount;
        this.direction = direction;
        this.partnerName = partnerName;
        this.partnerIban = partnerIban;
        this.description = description;
        this.ref = ref;
        this.balanceAfter = balanceAfter;
    }

    public UUID getId() { return id; }
    public UUID getStatementId() { return statementId; }
    public LocalDate getTxnDate() { return txnDate; }
    public BigDecimal getAmount() { return amount; }
    public TxnDirection getDirection() { return direction; }
    public String getPartnerName() { return partnerName; }
    public String getPartnerIban() { return partnerIban; }
    public String getDescription() { return description; }
    public String getRef() { return ref; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
}
```
- [ ] **Step 5:** `adapter/persistence/BankStatementRepository.java`
```java
package ro.myfinance.extraction.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.extraction.domain.BankStatement;

public interface BankStatementRepository extends JpaRepository<BankStatement, UUID> {

    List<BankStatement> findByCompanyIdAndPeriodMonth(UUID companyId, LocalDate periodMonth);
}
```
- [ ] **Step 6:** `adapter/persistence/BankTransactionRepository.java`
```java
package ro.myfinance.extraction.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.extraction.domain.BankTransaction;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {

    List<BankTransaction> findByStatementIdInOrderByTxnDateDesc(List<UUID> statementIds);
}
```
- [ ] **Step 7:** `mvn -B -DskipTests test-compile` → BUILD SUCCESS.
- [ ] **Step 8:** Commit: `git add backend/src/main/java/ro/myfinance/extraction/domain backend/src/main/java/ro/myfinance/extraction/adapter/persistence && git commit -m "feat(extraction): BankStatement + BankTransaction entities and repositories"`

---

## Task 3: Parser port + records + registry

**Files:** Create under `extraction/application/`.

- [ ] **Step 1:** `application/ParsedTransaction.java`
```java
package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A transaction line produced by a bank-statement parser (amount signed: negative = debit). */
public record ParsedTransaction(LocalDate date, BigDecimal amount, String partnerName,
                                String partnerIban, String description, String ref,
                                BigDecimal balanceAfter) {
}
```
- [ ] **Step 2:** `application/ParsedStatement.java`
```java
package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.util.List;

public record ParsedStatement(String bankCode, String accountIban, BigDecimal openingBalance,
                              BigDecimal closingBalance, List<ParsedTransaction> transactions) {
}
```
- [ ] **Step 3:** `application/BankStatementParser.java`
```java
package ro.myfinance.extraction.application;

/** Port: one implementation per bank. Deterministic; no LLM. */
public interface BankStatementParser {

    /** True if this parser recognizes the statement from its extracted text. */
    boolean supports(String pdfText);

    /** Parse the PDF bytes into a normalized statement. */
    ParsedStatement parse(byte[] pdf);
}
```
- [ ] **Step 4:** `application/BankStatementParserRegistry.java`
```java
package ro.myfinance.extraction.application;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/** Picks the first parser whose supports() matches the statement text. */
@Component
public class BankStatementParserRegistry {

    private final List<BankStatementParser> parsers;

    public BankStatementParserRegistry(List<BankStatementParser> parsers) {
        this.parsers = parsers;
    }

    public Optional<BankStatementParser> find(byte[] pdf) {
        String text = extractText(pdf);
        return parsers.stream().filter(p -> p.supports(text)).findFirst();
    }

    private String extractText(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
```
- [ ] **Step 5:** `mvn -B -DskipTests test-compile` → BUILD SUCCESS.
- [ ] **Step 6:** Commit: `git add backend/src/main/java/ro/myfinance/extraction/application && git commit -m "feat(extraction): BankStatementParser port + registry"`

---

## Task 4: Event wiring + extraction service (+ IT with stub parser)

**Files:** Create `intake/application/DocumentUploadedEvent.java`; modify `intake/application/DocumentService.java`; create `extraction/application/BankStatementExtractionService.java`, `extraction/application/StatementExtractionListener.java`; test `extraction/BankStatementExtractionServiceIT.java`.

- [ ] **Step 1:** `intake/application/DocumentUploadedEvent.java`
```java
package ro.myfinance.intake.application;

import java.time.LocalDate;
import java.util.UUID;
import ro.myfinance.intake.domain.DocumentType;

/** Published synchronously after a document is uploaded; extraction listens for BANK_STATEMENT. */
public record DocumentUploadedEvent(UUID documentId, UUID companyId, LocalDate periodMonth,
                                    DocumentType type, byte[] bytes) {
}
```
- [ ] **Step 2:** Modify `intake/application/DocumentService.java` — inject `ApplicationEventPublisher` and publish after save. Add the import `org.springframework.context.ApplicationEventPublisher`. Change the constructor + `upload`:
  - Add field `private final ApplicationEventPublisher events;` and add it as the last constructor parameter, assigning it.
  - In `upload`, after `Document saved = documents.save(doc);` and the audit line, add:
    ```java
        events.publishEvent(new DocumentUploadedEvent(saved.getId(), companyId, period, type, bytes));
    ```
  (Keep everything else identical.)
- [ ] **Step 3:** `extraction/application/BankStatementExtractionService.java`
```java
package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.extraction.adapter.persistence.BankStatementRepository;
import ro.myfinance.extraction.adapter.persistence.BankTransactionRepository;
import ro.myfinance.extraction.domain.BankStatement;
import ro.myfinance.extraction.domain.BankTransaction;
import ro.myfinance.extraction.domain.StatementStatus;
import ro.myfinance.extraction.domain.TxnDirection;

/** Parses a bank-statement document into transactions. Tenant-scoped via RLS. */
@Service
@Transactional
public class BankStatementExtractionService {

    private static final Logger log = LoggerFactory.getLogger(BankStatementExtractionService.class);
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final BankStatementParserRegistry registry;
    private final BankStatementRepository statements;
    private final BankTransactionRepository transactions;
    private final AuditRecorder audit;

    public BankStatementExtractionService(BankStatementParserRegistry registry,
                                          BankStatementRepository statements,
                                          BankTransactionRepository transactions,
                                          AuditRecorder audit) {
        this.registry = registry;
        this.statements = statements;
        this.transactions = transactions;
        this.audit = audit;
    }

    public void extract(UUID documentId, UUID companyId, LocalDate periodMonth, byte[] bytes) {
        UUID tenantId = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));

        Optional<BankStatementParser> parser = registry.find(bytes);
        if (parser.isEmpty()) {
            statements.save(new BankStatement(tenantId, documentId, companyId, periodMonth,
                    null, null, null, null, StatementStatus.NEEDS_REVIEW, false, 0));
            log.info("No parser matched statement document {} → NEEDS_REVIEW", documentId);
            return;
        }

        ParsedStatement parsed;
        try {
            parsed = parser.get().parse(bytes);
        } catch (RuntimeException e) {
            log.warn("Parse failed for document {}", documentId, e);
            statements.save(new BankStatement(tenantId, documentId, companyId, periodMonth,
                    null, null, null, null, StatementStatus.FAILED, false, 0));
            return;
        }

        boolean crossOk = crossCheck(parsed);
        StatementStatus status = crossOk ? StatementStatus.EXTRACTED : StatementStatus.NEEDS_REVIEW;
        BankStatement statement = statements.save(new BankStatement(tenantId, documentId, companyId,
                periodMonth, parsed.bankCode(), parsed.accountIban(), parsed.openingBalance(),
                parsed.closingBalance(), status, crossOk, parsed.transactions().size()));

        for (ParsedTransaction t : parsed.transactions()) {
            TxnDirection dir = t.amount().signum() < 0 ? TxnDirection.DEBIT : TxnDirection.CREDIT;
            transactions.save(new BankTransaction(tenantId, companyId, statement.getId(), t.date(),
                    t.amount(), dir, t.partnerName(), t.partnerIban(), t.description(), t.ref(),
                    t.balanceAfter()));
        }
        audit.record("STATEMENT_EXTRACTED", "bank_statement", statement.getId());
    }

    private boolean crossCheck(ParsedStatement p) {
        if (p.openingBalance() == null || p.closingBalance() == null) {
            return false;
        }
        BigDecimal sum = p.transactions().stream()
                .map(ParsedTransaction::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expected = p.openingBalance().add(sum);
        return expected.subtract(p.closingBalance()).abs().compareTo(TOLERANCE) <= 0;
    }
}
```
- [ ] **Step 4:** `extraction/application/StatementExtractionListener.java`
```java
package ro.myfinance.extraction.application;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ro.myfinance.intake.application.DocumentUploadedEvent;
import ro.myfinance.intake.domain.DocumentType;

/** Synchronously extracts bank statements when a document is uploaded. */
@Component
public class StatementExtractionListener {

    private final BankStatementExtractionService service;

    public StatementExtractionListener(BankStatementExtractionService service) {
        this.service = service;
    }

    @EventListener
    public void onDocumentUploaded(DocumentUploadedEvent e) {
        if (e.type() == DocumentType.BANK_STATEMENT) {
            service.extract(e.documentId(), e.companyId(), e.periodMonth(), e.bytes());
        }
    }
}
```
- [ ] **Step 5:** `backend/src/test/java/ro/myfinance/extraction/BankStatementExtractionServiceIT.java`
```java
package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.application.CompanyService;
import ro.myfinance.extraction.application.BankStatementParser;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;
import ro.myfinance.extraction.domain.BankStatement;
import ro.myfinance.extraction.domain.StatementStatus;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.support.AbstractPostgresIT;
import org.springframework.context.annotation.Import;

@Import(BankStatementExtractionServiceIT.StubParserConfig.class)
class BankStatementExtractionServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000b1");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b2");

    @Autowired DocumentService documents;
    @Autowired CompanyService companies;
    @Autowired ro.myfinance.extraction.adapter.persistence.BankStatementRepository statements;
    @Autowired ro.myfinance.extraction.adapter.persistence.BankTransactionRepository transactions;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    /** A parser that recognizes the marker "STUBBANK" and returns two transactions that cross-check. */
    @TestConfiguration
    static class StubParserConfig {
        @Bean
        BankStatementParser stubParser() {
            return new BankStatementParser() {
                @Override public boolean supports(String text) { return text.contains("STUBBANK"); }
                @Override public ParsedStatement parse(byte[] pdf) {
                    return new ParsedStatement("STUB", "RO00STUB", new BigDecimal("100.00"),
                            new BigDecimal("70.00"), List.of(
                            new ParsedTransaction(LocalDate.of(2026, 6, 3), new BigDecimal("-50.00"),
                                    "Supplier SRL", "RO11", "achizitie", "r1", new BigDecimal("50.00")),
                            new ParsedTransaction(LocalDate.of(2026, 6, 4), new BigDecimal("20.00"),
                                    "Client SRL", "RO22", "incasare", "r2", new BigDecimal("70.00"))));
                }
            };
        }
    }

    private UUID asTenantWithCompany(UUID tenantId) {
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STANDARD') on conflict do nothing",
                tenantId, "T-" + tenantId);
        return companies.create("Client SRL", "RO-BNK-" + UUID.randomUUID(), "SRL", "Cluj", null, null, null).getId();
    }

    // A tiny PDF whose text contains the STUBBANK marker.
    private static byte[] stubPdf() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("STUBBANK extras de cont");
                cs.endText();
            }
            var out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void parsesStatementOnUploadAndCrossChecks() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        var doc = documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", stubPdf());

        List<BankStatement> st = statements.findByCompanyIdAndPeriodMonth(companyId, LocalDate.of(2026, 6, 1));
        assertThat(st).hasSize(1);
        assertThat(st.get(0).getStatus()).isEqualTo(StatementStatus.EXTRACTED);
        assertThat(st.get(0).isCrossCheckOk()).isTrue();
        assertThat(st.get(0).getTxnCount()).isEqualTo(2);
        assertThat(transactions.findByStatementIdInOrderByTxnDateDesc(List.of(st.get(0).getId()))).hasSize(2);
        // unrelated: ensure the document itself classified as BANK_STATEMENT (extras de cont marker)
        assertThat(doc.getType().name()).isEqualTo("BANK_STATEMENT");
    }

    @Test
    void unsupportedBankMarksNeedsReview() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        // A PDF without the STUBBANK marker but still classified a statement (contains "extras de cont").
        byte[] pdf;
        try (org.apache.pdfbox.pdmodel.PDDocument d = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var p = new org.apache.pdfbox.pdmodel.PDPage(); d.addPage(p);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(d, p)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Extras de cont BRD necunoscut");
                cs.endText();
            }
            var out = new java.io.ByteArrayOutputStream(); d.save(out); pdf = out.toByteArray();
        }
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "x.pdf", "application/pdf", pdf);
        var st = statements.findByCompanyIdAndPeriodMonth(companyId, LocalDate.of(2026, 6, 1));
        assertThat(st).hasSize(1);
        assertThat(st.get(0).getStatus()).isEqualTo(StatementStatus.NEEDS_REVIEW);
    }

    @Test
    void tenantBCannotSeeTenantAStatements() throws Exception {
        UUID companyA = asTenantWithCompany(TENANT_A);
        documents.upload(companyA, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", stubPdf());
        asTenantWithCompany(TENANT_B);
        assertThat(statements.findByCompanyIdAndPeriodMonth(companyA, LocalDate.of(2026, 6, 1))).isEmpty();
    }
}
```
- [ ] **Step 6:** `cd backend && mvn -B test` → BUILD SUCCESS (unit tests pass; this IT skips without Docker). Confirm `mvn -B -DskipTests test-compile` clean.
- [ ] **Step 7:** Commit:
```bash
git add backend/src/main/java/ro/myfinance/intake/application/DocumentUploadedEvent.java backend/src/main/java/ro/myfinance/intake/application/DocumentService.java backend/src/main/java/ro/myfinance/extraction/application/BankStatementExtractionService.java backend/src/main/java/ro/myfinance/extraction/application/StatementExtractionListener.java backend/src/test/java/ro/myfinance/extraction/BankStatementExtractionServiceIT.java
git commit -m "feat(extraction): parse bank statements on upload (event-driven) + IT"
```

---

## Task 5: Read endpoints (statements + transactions)

**Files:** Create `extraction/adapter/web/BankStatementDtos.java`, `extraction/adapter/web/BankStatementController.java`. Add a query method to `BankStatementRepository` if needed.

- [ ] **Step 1:** `extraction/adapter/web/BankStatementDtos.java`
```java
package ro.myfinance.extraction.adapter.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import ro.myfinance.extraction.domain.BankStatement;
import ro.myfinance.extraction.domain.BankTransaction;

public final class BankStatementDtos {

    private BankStatementDtos() {
    }

    public record StatementResponse(UUID id, String bankCode, String accountIban,
                                    BigDecimal openingBalance, BigDecimal closingBalance,
                                    String status, boolean crossCheckOk, int txnCount) {
        public static StatementResponse from(BankStatement s) {
            return new StatementResponse(s.getId(), s.getBankCode(), s.getAccountIban(),
                    s.getOpeningBalance(), s.getClosingBalance(), s.getStatus().name(),
                    s.isCrossCheckOk(), s.getTxnCount());
        }
    }

    public record TransactionResponse(UUID id, UUID statementId, LocalDate txnDate, BigDecimal amount,
                                      String direction, String partnerName, String partnerIban,
                                      String description, BigDecimal balanceAfter) {
        public static TransactionResponse from(BankTransaction t) {
            return new TransactionResponse(t.getId(), t.getStatementId(), t.getTxnDate(), t.getAmount(),
                    t.getDirection().name(), t.getPartnerName(), t.getPartnerIban(), t.getDescription(),
                    t.getBalanceAfter());
        }
    }
}
```
- [ ] **Step 2:** `extraction/adapter/web/BankStatementController.java`
```java
package ro.myfinance.extraction.adapter.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.extraction.adapter.persistence.BankStatementRepository;
import ro.myfinance.extraction.adapter.persistence.BankTransactionRepository;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.StatementResponse;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.TransactionResponse;
import ro.myfinance.extraction.domain.BankStatement;

/** Read views over parsed bank statements/transactions. Firm staff only. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class BankStatementController {

    private final BankStatementRepository statements;
    private final BankTransactionRepository transactions;

    public BankStatementController(BankStatementRepository statements,
                                   BankTransactionRepository transactions) {
        this.statements = statements;
        this.transactions = transactions;
    }

    @GetMapping("/bank-statements")
    public List<StatementResponse> statements(@PathVariable UUID companyId,
                                              @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return statements.findByCompanyIdAndPeriodMonth(companyId, period).stream()
                .map(StatementResponse::from).toList();
    }

    @GetMapping("/bank-transactions")
    public List<TransactionResponse> transactions(@PathVariable UUID companyId,
                                                  @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        List<UUID> ids = statements.findByCompanyIdAndPeriodMonth(companyId, period).stream()
                .map(BankStatement::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return transactions.findByStatementIdInOrderByTxnDateDesc(ids).stream()
                .map(TransactionResponse::from).toList();
    }
}
```
- [ ] **Step 3:** `mvn -B test` → BUILD SUCCESS.
- [ ] **Step 4:** Commit: `git add backend/src/main/java/ro/myfinance/extraction/adapter/web && git commit -m "feat(extraction): bank statements + transactions read endpoints"`

---

## Task 6: Documents summary endpoint (intake)

**Files:** Modify `intake/adapter/persistence/DocumentRepository.java`; create `intake/adapter/web/DocumentSummaryController.java` and a DTO inside it (or in `DocumentDtos`). Add a service method on `DocumentService`.

- [ ] **Step 1:** Add to `DocumentRepository`:
```java
    java.util.List<Document> findByPeriodMonth(java.time.LocalDate periodMonth);
```
- [ ] **Step 2:** Add to `DocumentService` (a read method that aggregates per company):
```java
    @Transactional(readOnly = true)
    public java.util.List<CompanyDocSummary> summary(java.time.LocalDate periodMonth) {
        java.util.Map<java.util.UUID, int[]> acc = new java.util.HashMap<>();
        // int[]{fileCount, hasBank(0/1), hasInvRec(0/1)}
        for (Document d : documents.findByPeriodMonth(periodMonth.withDayOfMonth(1))) {
            int[] a = acc.computeIfAbsent(d.getCompanyId(), k -> new int[3]);
            a[0]++;
            if (d.getType() == ro.myfinance.intake.domain.DocumentType.BANK_STATEMENT) a[1] = 1;
            if (d.getType() == ro.myfinance.intake.domain.DocumentType.INVOICE
                    || d.getType() == ro.myfinance.intake.domain.DocumentType.RECEIPT) a[2] = 1;
        }
        return acc.entrySet().stream()
                .map(e -> new CompanyDocSummary(e.getKey(), e.getValue()[1] == 1, e.getValue()[2] == 1, e.getValue()[0]))
                .toList();
    }

    public record CompanyDocSummary(java.util.UUID companyId, boolean hasBankStatement,
                                    boolean hasInvoiceOrReceipt, int fileCount) {
    }
```
- [ ] **Step 3:** `intake/adapter/web/DocumentSummaryController.java`
```java
package ro.myfinance.intake.adapter.web;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.application.DocumentService.CompanyDocSummary;

/** Per-company document summary for the Statements list. Firm staff only. */
@RestController
@RequestMapping("/api/v1/documents")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class DocumentSummaryController {

    private final DocumentService service;

    public DocumentSummaryController(DocumentService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public List<CompanyDocSummary> summary(@RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return service.summary(period);
    }
}
```
- [ ] **Step 4:** `mvn -B test` → BUILD SUCCESS.
- [ ] **Step 5:** Commit: `git add backend/src/main/java/ro/myfinance/intake && git commit -m "feat(intake): per-company documents summary endpoint"`

---

## Task 7: Frontend API clients + i18n

**Files:** Modify `frontend/src/api/documents.ts`; create `frontend/src/api/bank.ts`; modify `frontend/src/i18n.ts`.

- [ ] **Step 1:** Append to `frontend/src/api/documents.ts` (keep existing exports):
```ts
export interface CompanyDocSummary {
  companyId: string;
  hasBankStatement: boolean;
  hasInvoiceOrReceipt: boolean;
  fileCount: number;
}

export const documentsSummaryApi = {
  summary: (period: string) =>
    api<CompanyDocSummary[]>(`/api/v1/documents/summary?period=${period}`),
};
```
(Add `documentsSummaryApi` as a new export; do not modify `documentsApi`.)
- [ ] **Step 2:** Create `frontend/src/api/bank.ts`:
```ts
import { api } from "../lib/apiClient";

export interface BankStatement {
  id: string;
  bankCode: string | null;
  accountIban: string | null;
  openingBalance: number | null;
  closingBalance: number | null;
  status: string;
  crossCheckOk: boolean;
  txnCount: number;
}

export interface BankTransaction {
  id: string;
  statementId: string;
  txnDate: string;
  amount: number;
  direction: "DEBIT" | "CREDIT";
  partnerName: string | null;
  partnerIban: string | null;
  description: string | null;
  balanceAfter: number | null;
}

export const bankApi = {
  statements: (companyId: string, period: string) =>
    api<BankStatement[]>(`/api/v1/companies/${companyId}/bank-statements?period=${period}`),
  transactions: (companyId: string, period: string) =>
    api<BankTransaction[]>(`/api/v1/companies/${companyId}/bank-transactions?period=${period}`),
};
```
- [ ] **Step 3:** Add i18n keys to `frontend/src/i18n.ts`. In `ro.translation` (after the `documentType.*` block):
```ts
      "statements.crumb": "Documente încărcate de reprezentanți",
      "statements.bankStatement": "Extras de cont",
      "statements.invoices": "Facturi / chitanțe",
      "statements.completeness": "Completitudine",
      "statements.reminder": "Email reminder",
      "statements.files": "Fișiere",
      "statements.transactions": "Tranzacții bancare",
      "statements.open": "Deschide",
      "statements.uploaded": "Încărcat",
      "statements.missing": "Lipsește",
      "recon.title": "Reconciliere",
      "recon.parsed": "Extras(e) procesat(e) în {{n}} tranzacții",
      "recon.date": "Dată",
      "recon.partner": "Partener / descriere",
      "recon.amount": "Sumă",
      "recon.noStatement": "Niciun extras de cont încărcat",
      "files.title": "Fișiere",
      "files.add": "Adaugă fișier",
      "files.preview": "Previzualizare",
      "files.none": "Niciun fișier.",
```
In `en.translation`:
```ts
      "statements.crumb": "Documents uploaded by representatives",
      "statements.bankStatement": "Bank statement",
      "statements.invoices": "Invoices / receipts",
      "statements.completeness": "Completeness",
      "statements.reminder": "Reminder email",
      "statements.files": "Files",
      "statements.transactions": "Bank transactions",
      "statements.open": "Open",
      "statements.uploaded": "Uploaded",
      "statements.missing": "Missing",
      "recon.title": "Reconciliation",
      "recon.parsed": "Statement(s) parsed into {{n}} transactions",
      "recon.date": "Date",
      "recon.partner": "Partner / description",
      "recon.amount": "Amount",
      "recon.noStatement": "No bank statement uploaded",
      "files.title": "Files",
      "files.add": "Add file",
      "files.preview": "Preview",
      "files.none": "No files.",
```
- [ ] **Step 4:** `cd frontend && npx tsc -b` → no errors.
- [ ] **Step 5:** Commit: `git add frontend/src/api/documents.ts frontend/src/api/bank.ts frontend/src/i18n.ts && git commit -m "feat(fe): bank + documents-summary API clients + i18n"`

---

## Task 8: Frontend — Statements list page (monthly company list)

**Files:** Replace `frontend/src/pages/Statements.tsx`. Create `frontend/src/components/MonthBar.tsx`.

- [ ] **Step 1:** Create `frontend/src/components/MonthBar.tsx`:
```tsx
/** Prev/next month navigator. `value` is yyyy-MM-01; calls onChange with the new yyyy-MM-01. */
export function MonthBar({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const d = new Date(value);
  const label = d.toLocaleDateString(undefined, { month: "long", year: "numeric" });
  const shift = (delta: number) => {
    const nd = new Date(d.getFullYear(), d.getMonth() + delta, 1);
    onChange(`${nd.getFullYear()}-${String(nd.getMonth() + 1).padStart(2, "0")}-01`);
  };
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
      <button onClick={() => shift(-1)} aria-label="Previous month">◀</button>
      <span style={{ minWidth: 140, textAlign: "center", fontWeight: 600 }}>{label}</span>
      <button onClick={() => shift(1)} aria-label="Next month">▶</button>
    </div>
  );
}
```
- [ ] **Step 2:** Replace `frontend/src/pages/Statements.tsx` entirely:
```tsx
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { documentsSummaryApi } from "../api/documents";
import { MonthBar } from "../components/MonthBar";
import { FilesModal } from "../components/FilesModal";
import { ReconModal } from "../components/ReconModal";

function pill(text: string, kind: "ok" | "bad" | "na") {
  const colors: Record<string, React.CSSProperties> = {
    ok: { background: "#dcfce7", color: "#166534" },
    bad: { background: "#fee2e2", color: "#991b1b" },
    na: { background: "var(--border)", color: "var(--text-muted)" },
  };
  return <span style={{ ...colors[kind], borderRadius: 999, padding: "2px 10px", fontSize: 12 }}>{text}</span>;
}

/** Statements & invoices — monthly company list (follows the prototype). */
export function Statements() {
  const { t } = useTranslation();
  const [period, setPeriod] = useState(() => new Date().toISOString().slice(0, 7) + "-01");
  const [filesFor, setFilesFor] = useState<{ id: string; name: string } | null>(null);
  const [reconFor, setReconFor] = useState<{ id: string; name: string } | null>(null);

  const companies = useQuery({ queryKey: ["companies"], queryFn: companiesApi.list });
  const summary = useQuery({
    queryKey: ["doc-summary", period],
    queryFn: () => documentsSummaryApi.summary(period),
  });

  const byCompany = new Map((summary.data ?? []).map((s) => [s.companyId, s]));

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="card">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <div>
            <div style={{ color: "var(--text-muted)", fontSize: 12.5 }}>{t("statements.crumb")}</div>
            <h1 style={{ margin: "2px 0 0" }}>{t("documents.title")}</h1>
          </div>
          <MonthBar value={period} onChange={setPeriod} />
        </div>
      </div>

      <div className="card">
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8 }}>{t("documents.company")}</th>
              <th style={{ padding: 8 }}>{t("statements.bankStatement")}</th>
              <th style={{ padding: 8 }}>{t("statements.invoices")}</th>
              <th style={{ padding: 8 }}>{t("statements.completeness")}</th>
              <th style={{ padding: 8 }}>{t("statements.reminder")}</th>
              <th style={{ padding: 8 }}>{t("statements.files")}</th>
              <th style={{ padding: 8 }} />
            </tr>
          </thead>
          <tbody>
            {(companies.data ?? []).map((c) => {
              const s = byCompany.get(c.id);
              const hasBank = s?.hasBankStatement ?? false;
              return (
                <tr key={c.id} style={{ borderTop: "1px solid var(--border)" }}>
                  <td style={{ padding: 8 }}>
                    <b>{c.legalName}</b>
                    <div style={{ color: "var(--text-muted)", fontSize: 12 }}>{c.cui}{c.locality ? ` · ${c.locality}` : ""}</div>
                  </td>
                  <td style={{ padding: 8 }}>{hasBank ? pill(t("statements.uploaded"), "ok") : pill(t("statements.missing"), "bad")}</td>
                  <td style={{ padding: 8 }}>{s?.hasInvoiceOrReceipt ? pill(t("statements.uploaded"), "ok") : pill(t("statements.missing"), "bad")}</td>
                  <td style={{ padding: 8 }}>{pill("—", "na")}</td>
                  <td style={{ padding: 8 }}>{pill("—", "na")}</td>
                  <td style={{ padding: 8 }}>
                    <button onClick={() => setFilesFor({ id: c.id, name: c.legalName })}>
                      {s?.fileCount ?? 0} {t("statements.files").toLowerCase()}
                    </button>
                  </td>
                  <td style={{ padding: 8, textAlign: "right", whiteSpace: "nowrap" }}>
                    <button
                      disabled={!hasBank}
                      title={hasBank ? "" : t("recon.noStatement")}
                      onClick={() => setReconFor({ id: c.id, name: c.legalName })}
                    >
                      {t("statements.transactions")}
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {filesFor && (
        <FilesModal companyId={filesFor.id} companyName={filesFor.name} period={period} onClose={() => setFilesFor(null)} />
      )}
      {reconFor && (
        <ReconModal companyId={reconFor.id} companyName={reconFor.name} period={period} onClose={() => setReconFor(null)} />
      )}
    </div>
  );
}
```
- [ ] **Step 3:** This references `FilesModal` and `ReconModal` (created in Tasks 9 & 10). To keep the build green between tasks, create temporary stubs now so Task 8 compiles, then Tasks 9/10 replace them. Create `frontend/src/components/FilesModal.tsx`:
```tsx
export function FilesModal(_: { companyId: string; companyName: string; period: string; onClose: () => void }) {
  return null;
}
```
and `frontend/src/components/ReconModal.tsx`:
```tsx
export function ReconModal(_: { companyId: string; companyName: string; period: string; onClose: () => void }) {
  return null;
}
```
- [ ] **Step 4:** `cd frontend && npm run lint && npm run build` → both succeed.
- [ ] **Step 5:** Commit: `git add frontend/src/pages/Statements.tsx frontend/src/components/MonthBar.tsx frontend/src/components/FilesModal.tsx frontend/src/components/ReconModal.tsx && git commit -m "feat(fe): Statements monthly company list + month bar (prototype)"`

---

## Task 9: Frontend — Files modal + document viewer

**Files:** Replace `frontend/src/components/FilesModal.tsx`.

- [ ] **Step 1:** Replace `frontend/src/components/FilesModal.tsx` entirely:
```tsx
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { documentsApi, type Document } from "../api/documents";

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(15,23,42,0.4)",
  display: "grid", placeItems: "center", zIndex: 50,
};

export function FilesModal({ companyId, companyName, period, onClose }:
  { companyId: string; companyName: string; period: string; onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data = [] } = useQuery({
    queryKey: ["documents", companyId, period],
    queryFn: () => documentsApi.list(companyId, period),
  });
  const [selId, setSelId] = useState<string | null>(null);
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);

  const selected: Document | undefined = data.find((d) => d.id === selId) ?? data[0];

  useEffect(() => {
    let revoked: string | null = null;
    if (selected) {
      void documentsApi.download(companyId, selected.id).then((blob) => {
        const url = URL.createObjectURL(blob);
        revoked = url;
        setBlobUrl(url);
      });
    } else {
      setBlobUrl(null);
    }
    return () => { if (revoked) URL.revokeObjectURL(revoked); };
  }, [companyId, selected?.id]);

  const upload = useMutation({
    mutationFn: () => documentsApi.upload(companyId, period, file!),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ["documents", companyId, period] }); void qc.invalidateQueries({ queryKey: ["doc-summary", period] }); setFile(null); },
  });
  const remove = useMutation({
    mutationFn: (id: string) => documentsApi.remove(companyId, id),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ["documents", companyId, period] }); void qc.invalidateQueries({ queryKey: ["doc-summary", period] }); },
  });

  return (
    <div style={overlay} onClick={onClose}>
      <div className="card" style={{ width: 820, maxWidth: "96vw" }} onClick={(e) => e.stopPropagation()}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h2 style={{ margin: 0 }}>{t("files.title")} — {companyName}</h2>
          <button onClick={onClose}>✕</button>
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "280px 1fr", gap: 14, marginTop: 12, alignItems: "start" }}>
          <div>
            <div style={{ maxHeight: 360, overflow: "auto" }}>
              {data.length === 0 && <div style={{ color: "var(--text-muted)" }}>{t("files.none")}</div>}
              {data.map((d) => (
                <div
                  key={d.id}
                  onClick={() => setSelId(d.id)}
                  style={{
                    display: "flex", alignItems: "center", gap: 8, padding: "8px 10px",
                    borderRadius: 9, cursor: "pointer", marginBottom: 4,
                    border: `1px solid ${selected?.id === d.id ? "var(--primary)" : "transparent"}`,
                    background: selected?.id === d.id ? "var(--primary-light, #eef2ff)" : "transparent",
                  }}
                >
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontWeight: 600, fontSize: 12.5, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{d.originalFilename}</div>
                    <div style={{ color: "var(--text-muted)", fontSize: 11 }}>{t(`documentType.${d.type}`, { defaultValue: d.type })}</div>
                  </div>
                  <button onClick={(e) => { e.stopPropagation(); remove.mutate(d.id); }} title="Delete"
                    style={{ border: "none", background: "none", color: "#dc2626", cursor: "pointer" }}>✕</button>
                </div>
              ))}
            </div>
            <form style={{ display: "flex", gap: 6, marginTop: 8 }} onSubmit={(e) => { e.preventDefault(); if (file) upload.mutate(); }}>
              <input type="file" accept="application/pdf,image/png,image/jpeg,image/webp" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
              <button className="primary" type="submit" disabled={!file || upload.isPending}>{t("files.add")}</button>
            </form>
          </div>
          <div>
            <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 6 }}>{selected?.originalFilename ?? t("files.preview")}</div>
            <div style={{ border: "1px solid var(--border)", borderRadius: 10, overflow: "hidden", height: 360, background: "#525659" }}>
              {blobUrl && selected?.contentType?.startsWith("image/") && (
                <img src={blobUrl} alt={selected.originalFilename} style={{ width: "100%", height: "100%", objectFit: "contain" }} />
              )}
              {blobUrl && selected?.contentType === "application/pdf" && (
                <iframe title="preview" src={blobUrl} style={{ width: "100%", height: "100%", border: "none" }} />
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
```
- [ ] **Step 2:** `cd frontend && npm run lint && npm run build` → both succeed.
- [ ] **Step 3:** Commit: `git add frontend/src/components/FilesModal.tsx && git commit -m "feat(fe): files modal with PDF/image document viewer"`

---

## Task 10: Frontend — Bank transactions (reconciliation) modal

**Files:** Replace `frontend/src/components/ReconModal.tsx`.

- [ ] **Step 1:** Replace `frontend/src/components/ReconModal.tsx` entirely:
```tsx
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { bankApi } from "../api/bank";

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(15,23,42,0.4)",
  display: "grid", placeItems: "center", zIndex: 50,
};

const fmt = (n: number) =>
  n.toLocaleString("ro-RO", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export function ReconModal({ companyId, companyName, period, onClose }:
  { companyId: string; companyName: string; period: string; onClose: () => void }) {
  const { t } = useTranslation();
  const statements = useQuery({ queryKey: ["bank-stmts", companyId, period], queryFn: () => bankApi.statements(companyId, period) });
  const txns = useQuery({ queryKey: ["bank-txns", companyId, period], queryFn: () => bankApi.transactions(companyId, period) });

  const list = txns.data ?? [];

  return (
    <div style={overlay} onClick={onClose}>
      <div className="card" style={{ width: 920, maxWidth: "97vw", maxHeight: "92vh", overflow: "auto" }} onClick={(e) => e.stopPropagation()}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h2 style={{ margin: 0 }}>{t("recon.title")} — {companyName}</h2>
          <button onClick={onClose}>✕</button>
        </div>
        <div style={{ color: "var(--text-muted)", fontSize: 12.5, margin: "4px 0 12px" }}>
          {t("recon.parsed", { n: list.length })}
        </div>

        {(statements.data ?? []).map((s) => (
          <div key={s.id} style={{ display: "flex", gap: 10, alignItems: "center", fontSize: 12.5, padding: "6px 0", borderBottom: "1px solid var(--border)" }}>
            <b>{s.bankCode ?? "—"}</b>
            <span style={{ color: "var(--text-muted)" }}>{s.accountIban ?? ""}</span>
            <span style={{ color: "var(--text-muted)" }}>
              {s.openingBalance != null ? fmt(s.openingBalance) : "—"} → {s.closingBalance != null ? fmt(s.closingBalance) : "—"}
            </span>
            <span style={{ color: s.crossCheckOk ? "#166534" : "#991b1b" }}>
              {s.crossCheckOk ? "✓" : "⚠"} {s.status}
            </span>
          </div>
        ))}

        <table style={{ width: "100%", borderCollapse: "collapse", marginTop: 12 }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8 }}>{t("recon.date")}</th>
              <th style={{ padding: 8 }}>{t("recon.partner")}</th>
              <th style={{ padding: 8, textAlign: "right" }}>{t("recon.amount")}</th>
            </tr>
          </thead>
          <tbody>
            {list.map((tx) => (
              <tr key={tx.id} style={{ borderTop: "1px solid var(--border)" }}>
                <td style={{ padding: 8, whiteSpace: "nowrap" }}>{tx.txnDate}</td>
                <td style={{ padding: 8 }}>
                  <b>{tx.partnerName ?? "—"}</b>
                  <div style={{ color: "var(--text-muted)", fontSize: 12 }}>
                    {[tx.description, tx.partnerIban].filter(Boolean).join(" · ")}
                  </div>
                </td>
                <td style={{ padding: 8, textAlign: "right", fontVariantNumeric: "tabular-nums", color: tx.amount < 0 ? "inherit" : "#166534" }}>
                  {tx.amount < 0 ? "-" : "+"}{fmt(Math.abs(tx.amount))}
                </td>
              </tr>
            ))}
            {list.length === 0 && (
              <tr><td colSpan={3} style={{ padding: 8, color: "var(--text-muted)" }}>—</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
```
- [ ] **Step 2:** `cd frontend && npm run lint && npm run build` → both succeed.
- [ ] **Step 3:** Commit: `git add frontend/src/components/ReconModal.tsx && git commit -m "feat(fe): bank transactions (reconciliation) modal"`

---

## Task 11: Final verification

- [ ] **Step 1:** `cd backend && mvn -B test` → BUILD SUCCESS (unit pass; ITs skip).
- [ ] **Step 2:** `cd frontend && npm run lint && npm run build` → both clean.
- [ ] **Step 3:** If any fix was needed, commit it.

---

## Self-review

**Spec coverage:** V5 tables (T1) · extraction entities/repos (T2) · parser port+registry (T3) · event-driven sync parse + cross-check + IT (T4) · statements/transactions read APIs (T5) · documents summary (T6) · FE clients+i18n (T7) · monthly list + month bar (T8) · files modal + viewer (T9) · transactions modal (T10) · verify (T11). Multi-statement per period: `bank_statement.document_id` unique, multiple per company/period, transactions unioned across statements (T5/T10). ✓

**Deviation from spec:** summary drops `hasTransactions` — the "Bank transactions" button is enabled by `hasBankStatement` (matches the prototype, avoids a cross-module query). Documented.

**Gated:** the concrete bank parser + fixture test (Task 12, not included) needs the real sample PDF. Until then no real `BankStatementParser` bean exists, so uploaded statements get `NEEDS_REVIEW` and the transactions modal shows none — the pipeline is proven by the stub-parser IT.

**Type consistency:** `ParsedStatement/ParsedTransaction` ↔ service ↔ entities ↔ DTOs ↔ `bankApi` types ↔ ReconModal. `CompanyDocSummary` ↔ summary endpoint ↔ `documentsSummaryApi` ↔ Statements page. Event `DocumentUploadedEvent(documentId, companyId, periodMonth, type, bytes)` published in DocumentService, consumed in listener.
