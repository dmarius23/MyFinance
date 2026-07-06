# Document-Requirement Engine + Reconciliation (Slice D) Implementation Plan

> **For agentic workers:** subagent-driven-development. Steps use `- [ ]` checkboxes.

**Goal:** Classify each bank transaction as needing a supporting document (deterministic rules + learned rules), let accountants override (and learn), dedupe transactions across overlapping statements, surface missing-docs + completeness, and complete the reconciliation screen.

**Architecture:** Extend `ro.myfinance.extraction`. A pure `TransactionClassifier` (base rules) + `ReconciliationService` (learned-rule override, classify-on-parse, accountant override, completeness). Ingestion-time dedup in `BankStatementExtractionService`. Flyway `V6` adds `transaction_rule` + `bank_transaction.account_iban`. Auto-matching deferred to Slice C (forward-compatible).

**Tech Stack:** Java 21 / Spring Boot 3.5.9 / JPA / Flyway / Testcontainers; React 18 / TS / Vite / TanStack Query / react-i18next.

---

## Context for the implementer
- RLS everywhere; tests bind `TenantContext` before DB ops, `clear()` in `@AfterEach` (see `BankStatementExtractionServiceIT`).
- Errors: `NotFoundException`(404)/`IllegalArgumentException`(400) in/handled by `ro.myfinance.common.web`.
- Audit: `AuditRecorder.record(action, entity, id)`.
- Docker NOT installed → `*IT` skip locally. Verify: `mvn -B -DskipTests test-compile`, `mvn -B test` (unit pass, ITs skip); FE `npm run lint && npm run build`.
- Flyway local `out-of-order: true`, so `V6` applies on the dev DB.
- Existing (Slice B): `BankTransaction` entity (parse fields only), `BankStatement`, `BankStatementRepository.findByCompanyIdAndPeriodMonth`, `BankTransactionRepository.findByStatementIdInOrderByTxnDateDesc`, `BankStatementExtractionService.extract(documentId, companyId, periodMonth, bytes)`, `BankStatementController` (reads), `BankStatementDtos.TransactionResponse`.

---

## Task 1: V6 migration

**File:** Create `backend/src/main/resources/db/migration/V6__transaction_rule.sql`
- [ ] **Step 1:**
```sql
ALTER TABLE bank_transaction ADD COLUMN account_iban text;

CREATE TABLE transaction_rule (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenant(id),
    company_id        uuid NOT NULL REFERENCES company(id),
    match_iban        text,
    match_desc_norm   text NOT NULL,
    requires_document boolean NOT NULL,
    created_by        uuid,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_txn_rule UNIQUE (tenant_id, company_id, match_iban, match_desc_norm)
);
CREATE INDEX idx_txn_rule_company ON transaction_rule(tenant_id, company_id);

ALTER TABLE transaction_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE transaction_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON transaction_rule
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON transaction_rule TO myfinance_app;
    END IF;
END $$;
```
- [ ] **Step 2:** `cd backend && mvn -B -DskipTests test-compile` → BUILD SUCCESS.
- [ ] **Step 3:** Commit: `git add backend/src/main/resources/db/migration/V6__transaction_rule.sql && git commit -m "feat(recon): V6 transaction_rule table + bank_transaction.account_iban"`

---

## Task 2: Enums, entities, repositories

**Files:** Create `DocCategory`, `DecisionSource`, `TransactionRule`, `TransactionRuleRepository`; replace `BankTransaction`; extend two repositories.

- [ ] **Step 1:** `extraction/domain/DocCategory.java`
```java
package ro.myfinance.extraction.domain;

public enum DocCategory {
    INCOME, TAX, OWN_TRANSFER, SALARY, FEE, LEASING, SUPPLIER
}
```
- [ ] **Step 2:** `extraction/domain/DecisionSource.java`
```java
package ro.myfinance.extraction.domain;

public enum DecisionSource {
    SYSTEM_RULE, LEARNED_RULE, ACCOUNTANT_SET
}
```
- [ ] **Step 3:** `extraction/domain/TransactionRule.java`
```java
package ro.myfinance.extraction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** A learned document-requirement rule, created from an accountant's override. */
@Entity
@Table(name = "transaction_rule")
public class TransactionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "match_iban")
    private String matchIban;

    @Column(name = "match_desc_norm", nullable = false)
    private String matchDescNorm;

    @Column(name = "requires_document", nullable = false)
    private boolean requiresDocument;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransactionRule() {
    }

    public TransactionRule(UUID tenantId, UUID companyId, String matchIban, String matchDescNorm,
                           boolean requiresDocument, UUID createdBy) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.matchIban = matchIban;
        this.matchDescNorm = matchDescNorm;
        this.requiresDocument = requiresDocument;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public String getMatchIban() { return matchIban; }
    public String getMatchDescNorm() { return matchDescNorm; }
    public boolean isRequiresDocument() { return requiresDocument; }
    public void setRequiresDocument(boolean requiresDocument) { this.requiresDocument = requiresDocument; }
}
```
- [ ] **Step 4:** `extraction/adapter/persistence/TransactionRuleRepository.java`
```java
package ro.myfinance.extraction.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.extraction.domain.TransactionRule;

public interface TransactionRuleRepository extends JpaRepository<TransactionRule, UUID> {

    List<TransactionRule> findByCompanyId(UUID companyId);
}
```
- [ ] **Step 5:** Replace `extraction/domain/BankTransaction.java` entirely (adds account_iban + requirement fields + getters/setters; keeps Slice-B fields):
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

    @Column(name = "account_iban")
    private String accountIban;

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

    @Column(name = "matched_document_id")
    private UUID matchedDocumentId;

    @Column(name = "requires_document", nullable = false)
    private boolean requiresDocument;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_source")
    private DecisionSource decisionSource;

    @Enumerated(EnumType.STRING)
    private DocCategory category;

    @Column(name = "override_reason")
    private String overrideReason;

    protected BankTransaction() {
    }

    public BankTransaction(UUID tenantId, UUID companyId, UUID statementId, String accountIban,
                           LocalDate txnDate, BigDecimal amount, TxnDirection direction,
                           String partnerName, String partnerIban, String description, String ref,
                           BigDecimal balanceAfter) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.statementId = statementId;
        this.accountIban = accountIban;
        this.txnDate = txnDate;
        this.amount = amount;
        this.direction = direction;
        this.partnerName = partnerName;
        this.partnerIban = partnerIban;
        this.description = description;
        this.ref = ref;
        this.balanceAfter = balanceAfter;
        this.requiresDocument = false;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getStatementId() { return statementId; }
    public String getAccountIban() { return accountIban; }
    public LocalDate getTxnDate() { return txnDate; }
    public BigDecimal getAmount() { return amount; }
    public TxnDirection getDirection() { return direction; }
    public String getPartnerName() { return partnerName; }
    public String getPartnerIban() { return partnerIban; }
    public String getDescription() { return description; }
    public String getRef() { return ref; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public UUID getMatchedDocumentId() { return matchedDocumentId; }
    public boolean isRequiresDocument() { return requiresDocument; }
    public DecisionSource getDecisionSource() { return decisionSource; }
    public DocCategory getCategory() { return category; }
    public String getOverrideReason() { return overrideReason; }

    public void setRequiresDocument(boolean requiresDocument) { this.requiresDocument = requiresDocument; }
    public void setDecisionSource(DecisionSource decisionSource) { this.decisionSource = decisionSource; }
    public void setCategory(DocCategory category) { this.category = category; }
    public void setOverrideReason(String overrideReason) { this.overrideReason = overrideReason; }
}
```
- [ ] **Step 6:** Add to `BankTransactionRepository`:
```java
    java.util.List<ro.myfinance.extraction.domain.BankTransaction> findByCompanyId(java.util.UUID companyId);
```
- [ ] **Step 7:** Add to `BankStatementRepository`:
```java
    java.util.List<ro.myfinance.extraction.domain.BankStatement> findByPeriodMonth(java.time.LocalDate periodMonth);
```
- [ ] **Step 8:** Compile: `cd backend && mvn -B -DskipTests test-compile` → BUILD SUCCESS. (The Slice-B `BankStatement(... )` constructor call in `BankStatementExtractionService` is unaffected; the `BankTransaction` constructor changed — Task 4 updates its caller, so a compile error there is expected until Task 4. To keep this task green, ALSO do the minimal caller fix now is NOT required — instead verify with `mvn -B -DskipTests compile` of main may fail on the BankTransaction constructor in BankStatementExtractionService. If it does, that's expected; proceed to commit the entity/repo changes and fix the caller in Task 4. To avoid a red build between tasks, this task's compile check is allowed to fail ONLY on `BankStatementExtractionService` for the changed `BankTransaction` constructor; everything else must compile.)

  Simpler: make Step 8 verification `mvn -B -DskipTests test-compile` and EXPECT a single compile error in `BankStatementExtractionService.java` (new BankTransaction(...) now needs `accountIban`). Fix it minimally in THIS task to stay green: in `BankStatementExtractionService.extract`, change the `new BankTransaction(tenantId, companyId, statement.getId(), t.date(), ...)` call to pass `parsed.accountIban()` as the 4th argument:
  `new BankTransaction(tenantId, companyId, statement.getId(), parsed.accountIban(), t.date(), t.amount(), dir, t.partnerName(), t.partnerIban(), t.description(), t.ref(), t.balanceAfter())`.
  Then `mvn -B -DskipTests test-compile` → BUILD SUCCESS.
- [ ] **Step 9:** Commit:
```bash
git add backend/src/main/java/ro/myfinance/extraction/domain/ backend/src/main/java/ro/myfinance/extraction/adapter/persistence/ backend/src/main/java/ro/myfinance/extraction/application/BankStatementExtractionService.java
git commit -m "feat(recon): requirement fields on BankTransaction, TransactionRule entity + repos"
```

---

## Task 3: TransactionClassifier (pure) + unit test

**Files:** Create `extraction/application/ReconText.java`, `extraction/application/TransactionClassifier.java`; test `extraction/TransactionClassifierTest.java`.

- [ ] **Step 1:** `extraction/application/ReconText.java`
```java
package ro.myfinance.extraction.application;

import java.text.Normalizer;

/** Text normalization for rule matching: lowercase, strip diacritics, collapse whitespace. */
public final class ReconText {

    private ReconText() {
    }

    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String stripped = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return stripped.toLowerCase().replaceAll("\\s+", " ").strip();
    }
}
```
- [ ] **Step 2:** `extraction/application/TransactionClassifier.java`
```java
package ro.myfinance.extraction.application;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import ro.myfinance.extraction.domain.DocCategory;

/**
 * Deterministic base-rule classifier (no LLM): decides whether a transaction requires a supporting
 * document and assigns a category. Learned-rule and accountant overrides are applied by
 * ReconciliationService on top of this base result.
 */
@Component
public class TransactionClassifier {

    private static final Pattern TREASURY = Pattern.compile("^RO\\d{2}TREZ.*");

    public record Input(boolean credit, String partnerIban, String partnerName, String description,
                        String ownAccountIban, String companyName) {
    }

    public record Result(boolean requiresDocument, DocCategory category) {
    }

    public Result classify(Input in) {
        if (in.credit()) {
            return new Result(false, DocCategory.INCOME);
        }
        if (in.partnerIban() != null && TREASURY.matcher(in.partnerIban()).matches()) {
            return new Result(false, DocCategory.TAX);
        }
        if (isOwnTransfer(in)) {
            return new Result(false, DocCategory.OWN_TRANSFER);
        }
        String desc = ReconText.normalize(in.description());
        String partner = ReconText.normalize(in.partnerName());
        if (desc.contains("salariu") || desc.contains("salary")) {
            return new Result(false, DocCategory.SALARY);
        }
        if (desc.contains("comision") || desc.contains("fee") || partner.contains("netopia")) {
            return new Result(false, DocCategory.FEE);
        }
        if (desc.contains("leasing")) {
            return new Result(true, DocCategory.LEASING);
        }
        return new Result(true, DocCategory.SUPPLIER);
    }

    private boolean isOwnTransfer(Input in) {
        if (in.partnerIban() != null && in.partnerIban().equals(in.ownAccountIban())) {
            return true;
        }
        String company = ReconText.normalize(in.companyName());
        String partner = ReconText.normalize(in.partnerName());
        return !company.isBlank() && !partner.isBlank()
                && (partner.contains(company) || company.contains(partner));
    }
}
```
- [ ] **Step 3:** `backend/src/test/java/ro/myfinance/extraction/TransactionClassifierTest.java`
```java
package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.application.TransactionClassifier;
import ro.myfinance.extraction.application.TransactionClassifier.Input;
import ro.myfinance.extraction.domain.DocCategory;

class TransactionClassifierTest {

    private final TransactionClassifier c = new TransactionClassifier();

    private Input debit(String partnerIban, String partnerName, String desc) {
        return new Input(false, partnerIban, partnerName, desc, "RO00OWN", "Innovatecode It Srl");
    }

    @Test
    void incomingIsNoDoc() {
        var r = c.classify(new Input(true, "RO11", "Client", "incasare", "RO00OWN", "Demo"));
        assertThat(r.requiresDocument()).isFalse();
        assertThat(r.category()).isEqualTo(DocCategory.INCOME);
    }

    @Test
    void treasuryIsTaxNoDoc() {
        var r = c.classify(debit("RO54TREZ21620A470300", "Trezoreria Cluj", "CAM"));
        assertThat(r.requiresDocument()).isFalse();
        assertThat(r.category()).isEqualTo(DocCategory.TAX);
    }

    @Test
    void ownTransferByIbanIsNoDoc() {
        var r = c.classify(new Input(false, "RO00OWN", "Self", "transfer", "RO00OWN", "Demo"));
        assertThat(r.category()).isEqualTo(DocCategory.OWN_TRANSFER);
        assertThat(r.requiresDocument()).isFalse();
    }

    @Test
    void ownTransferByNameIsNoDoc() {
        var r = c.classify(debit(null, "INNOVATECODE IT SRL", "transfer"));
        assertThat(r.category()).isEqualTo(DocCategory.OWN_TRANSFER);
    }

    @Test
    void salaryIsNoDoc() {
        var r = c.classify(debit("RO72BRDE", "Angajat", "salariu luna Ianuarie"));
        assertThat(r.category()).isEqualTo(DocCategory.SALARY);
        assertThat(r.requiresDocument()).isFalse();
    }

    @Test
    void feeIsNoDoc() {
        assertThat(c.classify(debit("RO45", "NETOPIA PAYMENTS", "comision lunar")).category())
                .isEqualTo(DocCategory.FEE);
    }

    @Test
    void leasingNeedsDoc() {
        var r = c.classify(debit("RO98BTRL", "BT Leasing", "rata leasing Martie"));
        assertThat(r.requiresDocument()).isTrue();
        assertThat(r.category()).isEqualTo(DocCategory.LEASING);
    }

    @Test
    void supplierNeedsDoc() {
        var r = c.classify(debit("RO21BRDE", "SELGROS", "achizitie marfa"));
        assertThat(r.requiresDocument()).isTrue();
        assertThat(r.category()).isEqualTo(DocCategory.SUPPLIER);
    }
}
```
- [ ] **Step 4:** `cd backend && mvn -B test` → BUILD SUCCESS; the 8 classifier tests pass.
- [ ] **Step 5:** Commit: `git add backend/src/main/java/ro/myfinance/extraction/application/ReconText.java backend/src/main/java/ro/myfinance/extraction/application/TransactionClassifier.java backend/src/test/java/ro/myfinance/extraction/TransactionClassifierTest.java && git commit -m "feat(recon): deterministic TransactionClassifier (base rules) + unit test"`

---

## Task 4: ReconciliationService (classify, override, completeness)

**Files:** Create `extraction/application/ReconciliationService.java`.

- [ ] **Step 1:** `extraction/application/ReconciliationService.java`
```java
package ro.myfinance.extraction.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.extraction.adapter.persistence.BankStatementRepository;
import ro.myfinance.extraction.adapter.persistence.BankTransactionRepository;
import ro.myfinance.extraction.adapter.persistence.TransactionRuleRepository;
import ro.myfinance.extraction.domain.BankStatement;
import ro.myfinance.extraction.domain.BankTransaction;
import ro.myfinance.extraction.domain.DecisionSource;
import ro.myfinance.extraction.domain.TransactionRule;

/** Document-requirement classification, accountant overrides (with learned rules), completeness. */
@Service
@Transactional
public class ReconciliationService {

    public enum Completeness { NOT_STARTED, PARTIAL, COMPLETE }

    public record CompanyCompleteness(UUID companyId, Completeness completeness) {
    }

    private final TransactionClassifier classifier;
    private final TransactionRuleRepository rules;
    private final BankTransactionRepository transactions;
    private final BankStatementRepository statements;
    private final CompanyRepository companies;
    private final AuditRecorder audit;

    public ReconciliationService(TransactionClassifier classifier, TransactionRuleRepository rules,
                                 BankTransactionRepository transactions, BankStatementRepository statements,
                                 CompanyRepository companies, AuditRecorder audit) {
        this.classifier = classifier;
        this.rules = rules;
        this.transactions = transactions;
        this.statements = statements;
        this.companies = companies;
        this.audit = audit;
    }

    /** Classify a freshly-parsed statement's transactions (skips any already set by an accountant). */
    public void classify(UUID statementId) {
        List<BankTransaction> txns = transactions.findByStatementIdInOrderByTxnDateDesc(List.of(statementId));
        if (txns.isEmpty()) {
            return;
        }
        UUID companyId = txns.get(0).getCompanyId();
        String companyName = companies.findById(companyId).map(c -> c.getLegalName()).orElse(null);
        List<TransactionRule> learned = rules.findByCompanyId(companyId);

        for (BankTransaction t : txns) {
            if (t.getDecisionSource() == DecisionSource.ACCOUNTANT_SET) {
                continue;
            }
            var base = classifier.classify(new TransactionClassifier.Input(
                    t.getAmount().signum() > 0, t.getPartnerIban(), t.getPartnerName(),
                    t.getDescription(), t.getAccountIban(), companyName));
            t.setCategory(base.category());
            TransactionRule rule = matchRule(learned, t);
            if (rule != null) {
                t.setRequiresDocument(rule.isRequiresDocument());
                t.setDecisionSource(DecisionSource.LEARNED_RULE);
            } else {
                t.setRequiresDocument(base.requiresDocument());
                t.setDecisionSource(DecisionSource.SYSTEM_RULE);
            }
        }
    }

    /** Accountant override: set the requirement, remember it as a learned rule. */
    public BankTransaction setRequirement(UUID txnId, boolean requiresDocument, String reason) {
        BankTransaction t = transactions.findById(txnId)
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + txnId));
        t.setRequiresDocument(requiresDocument);
        t.setDecisionSource(DecisionSource.ACCOUNTANT_SET);
        t.setOverrideReason(reason);

        UUID tenantId = TenantContext.tenantId().orElseThrow();
        UUID userId = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        String descNorm = ReconText.normalize(t.getDescription());
        TransactionRule existing = rules.findByCompanyId(t.getCompanyId()).stream()
                .filter(r -> Objects.equals(r.getMatchIban(), t.getPartnerIban())
                        && r.getMatchDescNorm().equals(descNorm))
                .findFirst().orElse(null);
        if (existing != null) {
            existing.setRequiresDocument(requiresDocument);
        } else {
            rules.save(new TransactionRule(tenantId, t.getCompanyId(), t.getPartnerIban(),
                    descNorm, requiresDocument, userId));
        }
        audit.record("TXN_REQUIREMENT_SET", "bank_transaction", txnId);
        return t;
    }

    @Transactional(readOnly = true)
    public List<CompanyCompleteness> completenessSummary(java.time.LocalDate periodMonth) {
        java.util.Map<UUID, List<BankStatement>> byCompany = new java.util.HashMap<>();
        for (BankStatement s : statements.findByPeriodMonth(periodMonth)) {
            byCompany.computeIfAbsent(s.getCompanyId(), k -> new java.util.ArrayList<>()).add(s);
        }
        List<CompanyCompleteness> out = new java.util.ArrayList<>();
        for (var e : byCompany.entrySet()) {
            List<UUID> stmtIds = e.getValue().stream().map(BankStatement::getId).toList();
            boolean missing = transactions.findByStatementIdInOrderByTxnDateDesc(stmtIds).stream()
                    .anyMatch(t -> t.isRequiresDocument() && t.getMatchedDocumentId() == null);
            out.add(new CompanyCompleteness(e.getKey(), missing ? Completeness.PARTIAL : Completeness.COMPLETE));
        }
        return out;
    }

    private TransactionRule matchRule(List<TransactionRule> learned, BankTransaction t) {
        String descNorm = ReconText.normalize(t.getDescription());
        return learned.stream()
                .filter(r -> Objects.equals(r.getMatchIban(), t.getPartnerIban())
                        && r.getMatchDescNorm().equals(descNorm))
                .findFirst().orElse(null);
    }
}
```
NOTE: confirm `Company` exposes `getLegalName()` and `CompanyRepository extends JpaRepository<Company, UUID>` (they do — used by `CompanyService`).
- [ ] **Step 2:** `cd backend && mvn -B -DskipTests test-compile` → BUILD SUCCESS.
- [ ] **Step 3:** Commit: `git add backend/src/main/java/ro/myfinance/extraction/application/ReconciliationService.java && git commit -m "feat(recon): ReconciliationService — classify, accountant override, completeness"`

---

## Task 5: Dedup + classify chaining + IT

**Files:** Modify `extraction/application/BankStatementExtractionService.java`; test `extraction/ReconciliationServiceIT.java`.

- [ ] **Step 1:** In `BankStatementExtractionService`, inject `ReconciliationService` (add constructor param + field) and `BankTransactionRepository` is already injected (`transactions`). Read the file. Replace the transaction-persistence section so it (a) sets `account_iban`, (b) dedupes against existing + intra-batch, (c) sets `txn_count` to the saved count, (d) classifies after saving. Concretely, replace the block from `boolean crossOk = crossCheck(parsed);` through the `for (ParsedTransaction t : parsed.transactions()) { ... }` loop and the `audit.record(...)` with:
```java
        boolean crossOk = crossCheck(parsed);
        StatementStatus status = crossOk ? StatementStatus.EXTRACTED : StatementStatus.NEEDS_REVIEW;

        // Dedup against existing transactions for this company and within this batch.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (BankTransaction existing : transactions.findByCompanyId(companyId)) {
            seen.add(dedupKey(existing.getAccountIban(), existing.getTxnDate(), existing.getAmount(),
                    existing.getBalanceAfter(), existing.getDescription(), existing.getRef()));
        }
        java.util.List<ParsedTransaction> unique = new java.util.ArrayList<>();
        for (ParsedTransaction t : parsed.transactions()) {
            String key = dedupKey(parsed.accountIban(), t.date(), t.amount(), t.balanceAfter(),
                    t.description(), t.ref());
            if (seen.add(key)) {
                unique.add(t);
            }
        }

        BankStatement statement = statements.save(new BankStatement(tenantId, documentId, companyId,
                periodMonth, parsed.bankCode(), parsed.accountIban(), parsed.openingBalance(),
                parsed.closingBalance(), status, crossOk, unique.size()));

        for (ParsedTransaction t : unique) {
            TxnDirection dir = t.amount().signum() < 0 ? TxnDirection.DEBIT : TxnDirection.CREDIT;
            transactions.save(new BankTransaction(tenantId, companyId, statement.getId(),
                    parsed.accountIban(), t.date(), t.amount(), dir, t.partnerName(), t.partnerIban(),
                    t.description(), t.ref(), t.balanceAfter()));
        }
        reconciliation.classify(statement.getId());
        audit.record("STATEMENT_EXTRACTED", "bank_statement", statement.getId());
```
Then add this helper method to the class:
```java
    private String dedupKey(String accountIban, java.time.LocalDate date, java.math.BigDecimal amount,
                            java.math.BigDecimal balanceAfter, String description, String ref) {
        if (balanceAfter != null) {
            return "B|" + accountIban + "|" + date + "|" + amount.stripTrailingZeros().toPlainString()
                    + "|" + balanceAfter.stripTrailingZeros().toPlainString();
        }
        return "F|" + date + "|" + amount.stripTrailingZeros().toPlainString() + "|"
                + ReconText.normalize(description) + "|" + (ref == null ? "" : ref);
    }
```
Add imports as needed: `ro.myfinance.extraction.domain.BankTransaction` and `ro.myfinance.extraction.application.ReconText` are same-package (no import needed for ReconText); `BankTransaction`/`BankStatement`/`TxnDirection`/`StatementStatus` already imported from Slice B. The earlier per-task fix (Task 2 Step 8) already added `parsed.accountIban()` to the `new BankTransaction(...)` — this block supersedes it; keep the version above.
- [ ] **Step 2:** `backend/src/test/java/ro/myfinance/extraction/ReconciliationServiceIT.java`
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
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.application.CompanyService;
import ro.myfinance.extraction.adapter.persistence.BankStatementRepository;
import ro.myfinance.extraction.adapter.persistence.BankTransactionRepository;
import ro.myfinance.extraction.application.BankStatementParser;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;
import ro.myfinance.extraction.application.ReconciliationService;
import ro.myfinance.extraction.domain.BankStatement;
import ro.myfinance.extraction.domain.BankTransaction;
import ro.myfinance.extraction.domain.DecisionSource;
import ro.myfinance.extraction.domain.DocCategory;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.support.AbstractPostgresIT;

@Import(ReconciliationServiceIT.StubConfig.class)
class ReconciliationServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e1");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000e2");

    @Autowired DocumentService documents;
    @Autowired CompanyService companies;
    @Autowired ReconciliationService reconciliation;
    @Autowired BankStatementRepository statements;
    @Autowired BankTransactionRepository txns;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    /** A parser returning a fixed statement (supplier debit + treasury debit + incoming credit). */
    @TestConfiguration
    static class StubConfig {
        @Bean
        @Order(0)
        BankStatementParser stub() {
            return new BankStatementParser() {
                @Override public boolean supports(String text) { return text.contains("RECONSTUB"); }
                @Override public ParsedStatement parse(String t) {
                    return new ParsedStatement("STUB", "RO00OWN", new BigDecimal("1000.00"),
                            new BigDecimal("1170.00"), List.of(
                            new ParsedTransaction(LocalDate.of(2026, 6, 3), new BigDecimal("-200.00"),
                                    "SELGROS", "RO21SUPP", "achizitie marfa", "r1", new BigDecimal("800.00")),
                            new ParsedTransaction(LocalDate.of(2026, 6, 4), new BigDecimal("-30.00"),
                                    "Trezoreria Cluj", "RO54TREZ21620A470300", "CAM", "r2", new BigDecimal("770.00")),
                            new ParsedTransaction(LocalDate.of(2026, 6, 5), new BigDecimal("400.00"),
                                    "AROBIS", "RO11CLI", "incasare", "r3", new BigDecimal("1170.00"))));
                }
            };
        }
    }

    private static byte[] pdf(String marker) throws Exception {
        try (var d = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var p = new org.apache.pdfbox.pdmodel.PDPage(); d.addPage(p);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(d, p)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(marker + " extras de cont");
                cs.endText();
            }
            var out = new java.io.ByteArrayOutputStream(); d.save(out); return out.toByteArray();
        }
    }

    private UUID asTenantWithCompany(UUID tenantId) {
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STANDARD') on conflict do nothing",
                tenantId, "T-" + tenantId);
        return companies.create("Client SRL", "RO-REC-" + UUID.randomUUID(), "SRL", "Cluj", null, null, null).getId();
    }

    private List<BankTransaction> companyTxns(UUID companyId) {
        List<UUID> ids = statements.findByCompanyIdAndPeriodMonth(companyId, LocalDate.of(2026, 6, 1))
                .stream().map(BankStatement::getId).toList();
        return txns.findByStatementIdInOrderByTxnDateDesc(ids);
    }

    @Test
    void classifiesOnUpload() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));

        List<BankTransaction> list = companyTxns(companyId);
        assertThat(list).hasSize(3);
        BankTransaction supplier = list.stream().filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();
        assertThat(supplier.isRequiresDocument()).isTrue();
        assertThat(supplier.getCategory()).isEqualTo(DocCategory.SUPPLIER);
        assertThat(supplier.getDecisionSource()).isEqualTo(DecisionSource.SYSTEM_RULE);

        BankTransaction tax = list.stream().filter(t -> t.getCategory() == DocCategory.TAX).findFirst().orElseThrow();
        assertThat(tax.isRequiresDocument()).isFalse();
        BankTransaction income = list.stream().filter(t -> t.getCategory() == DocCategory.INCOME).findFirst().orElseThrow();
        assertThat(income.isRequiresDocument()).isFalse();
    }

    @Test
    void dedupAcrossReupload() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras-again.pdf", "application/pdf", pdf("RECONSTUB"));
        assertThat(companyTxns(companyId)).hasSize(3); // not 6
    }

    @Test
    void overrideCreatesLearnedRuleAppliedToNextStatement() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        BankTransaction supplier = companyTxns(companyId).stream()
                .filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();

        reconciliation.setRequirement(supplier.getId(), false, "no doc needed");
        BankTransaction after = txns.findById(supplier.getId()).orElseThrow();
        assertThat(after.isRequiresDocument()).isFalse();
        assertThat(after.getDecisionSource()).isEqualTo(DecisionSource.ACCOUNTANT_SET);

        // A second statement for July with the same counterparty+description inherits the learned rule.
        documents.upload(companyId, LocalDate.of(2026, 7, 1), "iulie.pdf", "application/pdf", pdf("RECONSTUB"));
        List<UUID> julyIds = statements.findByCompanyIdAndPeriodMonth(companyId, LocalDate.of(2026, 7, 1))
                .stream().map(BankStatement::getId).toList();
        BankTransaction julySupplier = txns.findByStatementIdInOrderByTxnDateDesc(julyIds).stream()
                .filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();
        assertThat(julySupplier.isRequiresDocument()).isFalse();
        assertThat(julySupplier.getDecisionSource()).isEqualTo(DecisionSource.LEARNED_RULE);
    }

    @Test
    void completenessReflectsMissingDocs() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        var summary = reconciliation.completenessSummary(LocalDate.of(2026, 6, 1));
        assertThat(summary).anySatisfy(c -> {
            assertThat(c.companyId()).isEqualTo(companyId);
            assertThat(c.completeness()).isEqualTo(ReconciliationService.Completeness.PARTIAL);
        });
    }

    @Test
    void tenantBSeesNoTenantATransactions() throws Exception {
        UUID companyA = asTenantWithCompany(TENANT_A);
        documents.upload(companyA, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        asTenantWithCompany(TENANT_B);
        assertThat(companyTxns(companyA)).isEmpty();
    }
}
```
- [ ] **Step 3:** `cd backend && mvn -B test` → BUILD SUCCESS (unit tests pass; `ReconciliationServiceIT` skips without Docker). Confirm `mvn -B -DskipTests test-compile` clean.
- [ ] **Step 4:** Commit:
```bash
git add backend/src/main/java/ro/myfinance/extraction/application/BankStatementExtractionService.java backend/src/test/java/ro/myfinance/extraction/ReconciliationServiceIT.java
git commit -m "feat(recon): ingestion dedup + classify-on-parse chaining + IT"
```

---

## Task 6: Enrich TransactionResponse + override & summary endpoints

**Files:** Modify `extraction/adapter/web/BankStatementDtos.java`, `extraction/adapter/web/BankStatementController.java`; create `extraction/adapter/web/ReconciliationController.java`.

- [ ] **Step 1:** In `BankStatementDtos`, replace the `TransactionResponse` record with one carrying the requirement fields + a derived reason:
```java
    public record TransactionResponse(UUID id, UUID statementId, LocalDate txnDate, BigDecimal amount,
                                      String direction, String partnerName, String partnerIban,
                                      String description, BigDecimal balanceAfter,
                                      boolean requiresDocument, boolean matched, String category,
                                      String decisionSource, String reason) {
        public static TransactionResponse from(BankTransaction t) {
            return new TransactionResponse(t.getId(), t.getStatementId(), t.getTxnDate(), t.getAmount(),
                    t.getDirection().name(), t.getPartnerName(), t.getPartnerIban(), t.getDescription(),
                    t.getBalanceAfter(), t.isRequiresDocument(), t.getMatchedDocumentId() != null,
                    t.getCategory() == null ? null : t.getCategory().name(),
                    t.getDecisionSource() == null ? null : t.getDecisionSource().name(),
                    reason(t));
        }

        private static String reason(BankTransaction t) {
            if (t.getDecisionSource() == ro.myfinance.extraction.domain.DecisionSource.ACCOUNTANT_SET) {
                return t.getOverrideReason() != null ? t.getOverrideReason()
                        : "Set by accountant — learned rule saved";
            }
            if (t.getDecisionSource() == ro.myfinance.extraction.domain.DecisionSource.LEARNED_RULE) {
                return "Remembered from a past decision";
            }
            if (t.getCategory() == null) {
                return "";
            }
            return switch (t.getCategory()) {
                case INCOME -> "Incoming receipt — no purchase document";
                case TAX -> "Paid to Treasury — covered by declaration";
                case OWN_TRANSFER -> "Transfer between own accounts — no document";
                case SALARY -> "Covered by payroll / D112";
                case FEE -> "Bank/processor fee — no document";
                case LEASING -> "Leasing invoice / schedule required";
                case SUPPLIER -> "Supplier purchase — invoice required";
            };
        }
    }
```
Add the import `import ro.myfinance.extraction.domain.BankTransaction;` if not present (it is, for the existing record).
- [ ] **Step 2:** Add an override endpoint + a request DTO. In `BankStatementDtos` add:
```java
    public record SetRequirementRequest(boolean requiresDocument, String reason) {
    }
```
- [ ] **Step 3:** Create `extraction/adapter/web/ReconciliationController.java`:
```java
package ro.myfinance.extraction.adapter.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.SetRequirementRequest;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.TransactionResponse;
import ro.myfinance.extraction.application.ReconciliationService;
import ro.myfinance.extraction.application.ReconciliationService.CompanyCompleteness;

/** Reconciliation: accountant override + completeness summary. Firm staff only. */
@RestController
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @PatchMapping("/api/v1/companies/{companyId}/bank-transactions/{txnId}/requirement")
    public TransactionResponse setRequirement(@PathVariable UUID companyId, @PathVariable UUID txnId,
                                              @RequestBody SetRequirementRequest r) {
        return TransactionResponse.from(service.setRequirement(txnId, r.requiresDocument(), r.reason()));
    }

    @GetMapping("/api/v1/reconciliation/summary")
    public List<CompanyCompleteness> summary(@RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return service.completenessSummary(period);
    }
}
```
- [ ] **Step 4:** `cd backend && mvn -B test` → BUILD SUCCESS.
- [ ] **Step 5:** Commit: `git add backend/src/main/java/ro/myfinance/extraction/adapter/web/ && git commit -m "feat(recon): enriched transaction DTO + override & completeness endpoints"`

---

## Task 7: Frontend API + types + i18n

**Files:** Modify `frontend/src/api/bank.ts`, `frontend/src/i18n.ts`.

- [ ] **Step 1:** In `frontend/src/api/bank.ts`, extend the `BankTransaction` interface with the new fields and add the recon calls. Replace the `BankTransaction` interface and append the new API object — final file:
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
  requiresDocument: boolean;
  matched: boolean;
  category: string | null;
  decisionSource: string | null;
  reason: string;
}

export interface CompanyCompleteness {
  companyId: string;
  completeness: "NOT_STARTED" | "PARTIAL" | "COMPLETE";
}

export const bankApi = {
  statements: (companyId: string, period: string) =>
    api<BankStatement[]>(`/api/v1/companies/${companyId}/bank-statements?period=${period}`),
  transactions: (companyId: string, period: string) =>
    api<BankTransaction[]>(`/api/v1/companies/${companyId}/bank-transactions?period=${period}`),
  setRequirement: (companyId: string, txnId: string, requiresDocument: boolean, reason?: string) =>
    api<BankTransaction>(`/api/v1/companies/${companyId}/bank-transactions/${txnId}/requirement`, {
      method: "PATCH",
      body: JSON.stringify({ requiresDocument, reason }),
    }),
};

export const reconciliationApi = {
  summary: (period: string) =>
    api<CompanyCompleteness[]>(`/api/v1/reconciliation/summary?period=${period}`),
};
```
- [ ] **Step 2:** Add i18n keys to `frontend/src/i18n.ts`. In `ro.translation` (after the `files.*` block):
```ts
      "recon.document": "Document",
      "recon.reason": "Motiv",
      "recon.decidedBy": "Stabilit de",
      "recon.accountantSets": "Contabil decide",
      "recon.needsDoc": "Necesită document",
      "recon.notNeeded": "Nu e necesar",
      "recon.noDoc": "Fără document",
      "recon.source.SYSTEM_RULE": "Regulă de bază",
      "recon.source.LEARNED_RULE": "Învățat",
      "recon.source.ACCOUNTANT_SET": "Contabil",
      "recon.remembered": "memorat",
      "recon.missingTitle": "Documente necesare de la reprezentant",
      "recon.requestClient": "Solicită de la client",
      "recon.requestPreview": "Reminder pregătit (previzualizare) pentru tranzacțiile care necesită document.",
      "completeness.COMPLETE": "Complet",
      "completeness.PARTIAL": "Parțial",
      "completeness.NOT_STARTED": "Neînceput",
```
In `en.translation`:
```ts
      "recon.document": "Document",
      "recon.reason": "Reason",
      "recon.decidedBy": "Decided by",
      "recon.accountantSets": "Accountant sets",
      "recon.needsDoc": "Needs doc",
      "recon.notNeeded": "Not needed",
      "recon.noDoc": "No doc",
      "recon.source.SYSTEM_RULE": "Base rule",
      "recon.source.LEARNED_RULE": "Learned",
      "recon.source.ACCOUNTANT_SET": "Accountant",
      "recon.remembered": "remembered",
      "recon.missingTitle": "Documents the representative must provide",
      "recon.requestClient": "Request from client",
      "recon.requestPreview": "A reminder listing the document-needing transactions was prepared (preview).",
      "completeness.COMPLETE": "Complete",
      "completeness.PARTIAL": "Partial",
      "completeness.NOT_STARTED": "Not started",
```
- [ ] **Step 3:** `cd frontend && npx tsc -b` → no errors.
- [ ] **Step 4:** Commit: `git add frontend/src/api/bank.ts frontend/src/i18n.ts && git commit -m "feat(fe): recon API (override, completeness) + types + i18n"`

---

## Task 8: ReconModal completion

**Files:** Replace `frontend/src/components/ReconModal.tsx`.

- [ ] **Step 1:** Replace `frontend/src/components/ReconModal.tsx` entirely:
```tsx
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { bankApi } from "../api/bank";

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(15,23,42,0.4)",
  display: "grid", placeItems: "center", zIndex: 50,
};
const fmt = (n: number) => n.toLocaleString("ro-RO", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export function ReconModal({ companyId, companyName, period, onClose }:
  { companyId: string; companyName: string; period: string; onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const statements = useQuery({ queryKey: ["bank-stmts", companyId, period], queryFn: () => bankApi.statements(companyId, period) });
  const txns = useQuery({ queryKey: ["bank-txns", companyId, period], queryFn: () => bankApi.transactions(companyId, period) });

  const setReq = useMutation({
    mutationFn: ({ id, requiresDocument }: { id: string; requiresDocument: boolean }) =>
      bankApi.setRequirement(companyId, id, requiresDocument),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["bank-txns", companyId, period] }),
  });

  const list = txns.data ?? [];
  const missing = list.filter((tx) => tx.requiresDocument && !tx.matched);

  const requestFromClient = () => window.alert(t("recon.requestPreview"));

  return (
    <div style={overlay} onClick={onClose}>
      <div className="card" style={{ width: 1000, maxWidth: "97vw", maxHeight: "92vh", overflow: "auto" }} onClick={(e) => e.stopPropagation()}>
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
            <span style={{ color: s.crossCheckOk ? "#166534" : "#991b1b" }}>{s.crossCheckOk ? "✓" : "⚠"} {s.status}</span>
          </div>
        ))}

        {missing.length > 0 && (
          <div style={{ border: "1px dashed #dc2626", background: "#fef2f2", borderRadius: 10, padding: 12, margin: "12px 0" }}>
            <div style={{ color: "#991b1b", fontWeight: 600, fontSize: 13, marginBottom: 8 }}>
              ⚠ {t("recon.missingTitle")} — {missing.length}
            </div>
            {missing.map((m) => (
              <div key={m.id} style={{ display: "flex", justifyContent: "space-between", fontSize: 13, padding: "3px 0" }}>
                <span><b>{m.txnDate}</b> · {m.partnerName ?? "—"} <span style={{ color: "var(--text-muted)" }}>({m.category ?? "—"})</span></span>
                <span>{fmt(Math.abs(m.amount))} RON</span>
              </div>
            ))}
          </div>
        )}

        <table style={{ width: "100%", borderCollapse: "collapse", marginTop: 12 }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8 }}>{t("recon.date")}</th>
              <th style={{ padding: 8 }}>{t("recon.partner")}</th>
              <th style={{ padding: 8, textAlign: "right" }}>{t("recon.amount")}</th>
              <th style={{ padding: 8 }}>{t("recon.document")}</th>
              <th style={{ padding: 8 }}>{t("recon.reason")}</th>
              <th style={{ padding: 8 }}>{t("recon.decidedBy")}</th>
              <th style={{ padding: 8, textAlign: "center" }}>{t("recon.accountantSets")}</th>
            </tr>
          </thead>
          <tbody>
            {list.map((tx) => (
              <tr key={tx.id} style={{ borderTop: "1px solid var(--border)", background: tx.requiresDocument && !tx.matched ? "#fff7f6" : undefined }}>
                <td style={{ padding: 8, whiteSpace: "nowrap" }}>{tx.txnDate}</td>
                <td style={{ padding: 8 }}>
                  <b>{tx.partnerName ?? "—"}</b>
                  <div style={{ color: "var(--text-muted)", fontSize: 12 }}>{[tx.description, tx.partnerIban].filter(Boolean).join(" · ")}</div>
                </td>
                <td style={{ padding: 8, textAlign: "right", fontVariantNumeric: "tabular-nums", color: tx.amount < 0 ? "inherit" : "#166534" }}>
                  {tx.amount < 0 ? "-" : "+"}{fmt(Math.abs(tx.amount))}
                </td>
                <td style={{ padding: 8, whiteSpace: "nowrap" }}>
                  {tx.requiresDocument
                    ? <span style={{ color: "#991b1b" }}>⚠ {t("recon.needsDoc")}</span>
                    : <span style={{ color: "var(--text-muted)" }}>{t("recon.notNeeded")}</span>}
                </td>
                <td style={{ padding: 8, fontSize: 12, color: "var(--text-muted)" }}>
                  {tx.reason}
                  {tx.decisionSource === "LEARNED_RULE" || tx.decisionSource === "ACCOUNTANT_SET"
                    ? <span style={{ marginLeft: 6, background: "#ede9fe", color: "#6d28d9", borderRadius: 999, padding: "1px 6px", fontSize: 10 }}>✓ {t("recon.remembered")}</span>
                    : null}
                </td>
                <td style={{ padding: 8, fontSize: 12 }}>
                  {tx.decisionSource ? t(`recon.source.${tx.decisionSource}`) : "—"}
                </td>
                <td style={{ padding: 8, textAlign: "center", whiteSpace: "nowrap" }}>
                  <button
                    disabled={setReq.isPending}
                    onClick={() => setReq.mutate({ id: tx.id, requiresDocument: true })}
                    style={{ fontWeight: tx.requiresDocument ? 700 : 400 }}
                  >{t("recon.needsDoc")}</button>{" "}
                  <button
                    disabled={setReq.isPending}
                    onClick={() => setReq.mutate({ id: tx.id, requiresDocument: false })}
                    style={{ fontWeight: !tx.requiresDocument ? 700 : 400 }}
                  >{t("recon.noDoc")}</button>
                </td>
              </tr>
            ))}
            {list.length === 0 && (
              <tr><td colSpan={7} style={{ padding: 8, color: "var(--text-muted)" }}>—</td></tr>
            )}
          </tbody>
        </table>

        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8, marginTop: 14 }}>
          <button onClick={onClose}>{t("recon.notNeeded") && "Close"}</button>
          <button className="primary" disabled={missing.length === 0} onClick={requestFromClient}>
            {t("recon.requestClient")} ({missing.length})
          </button>
        </div>
      </div>
    </div>
  );
}
```
- [ ] **Step 2:** `cd frontend && npm run lint && npm run build` → both succeed.
- [ ] **Step 3:** Commit: `git add frontend/src/components/ReconModal.tsx && git commit -m "feat(fe): complete recon modal — needs-doc, override toggle, missing panel, request preview"`

---

## Task 9: Statements completeness column

**Files:** Modify `frontend/src/pages/Statements.tsx`.

- [ ] **Step 1:** Read `frontend/src/pages/Statements.tsx`. Add the reconciliation summary query and render the real Completeness pill. Specifically:
  - Add import: `import { reconciliationApi } from "../api/bank";`
  - Add a query alongside the existing `summary` query:
    ```tsx
    const completeness = useQuery({
      queryKey: ["recon-summary", period],
      queryFn: () => reconciliationApi.summary(period),
    });
    const completenessBy = new Map((completeness.data ?? []).map((c) => [c.companyId, c.completeness]));
    ```
  - Replace the placeholder Completeness cell `<td style={{ padding: 8 }}>{pill("—", "na")}</td>` (the first placeholder, the Completeness column) with:
    ```tsx
    <td style={{ padding: 8 }}>{(() => {
      const cs = completenessBy.get(c.id) ?? "NOT_STARTED";
      const kind = cs === "COMPLETE" ? "ok" : cs === "PARTIAL" ? "bad" : "na";
      return pill(t(`completeness.${cs}`), kind as "ok" | "bad" | "na");
    })()}</td>
    ```
  Leave the Reminder column placeholder as-is.
- [ ] **Step 2:** `cd frontend && npm run lint && npm run build` → both succeed.
- [ ] **Step 3:** Commit: `git add frontend/src/pages/Statements.tsx && git commit -m "feat(fe): real Completeness column on Statements list"`

---

## Task 10: Final verification
- [ ] **Step 1:** `cd backend && mvn -B test` → BUILD SUCCESS (unit pass; ITs skip).
- [ ] **Step 2:** `cd frontend && npm run lint && npm run build` → both clean.
- [ ] **Step 3:** Commit any fixes.

---

## Self-review
**Spec coverage:** V6 (T1) · enums/entities/repos incl. account_iban + requirement fields (T2) · pure classifier + tests (T3) · ReconciliationService classify/override/completeness (T4) · dedup + classify-on-parse + IT incl. dedup/learned/completeness/cross-tenant (T5) · enriched DTO + override & summary endpoints (T6) · FE api/types/i18n (T7) · recon modal completion (T8) · completeness column (T9) · verify (T10). Auto-match deferred; completeness keys off `requires_document && !matched` (forward-compatible). ✓

**Type consistency:** `TransactionClassifier.Input/Result` ↔ `ReconciliationService` ↔ enriched `TransactionResponse` ↔ `bankApi.BankTransaction` ↔ `ReconModal`. `CompanyCompleteness{companyId, completeness}` ↔ `/reconciliation/summary` ↔ `reconciliationApi` ↔ Statements page. Dedup key uses `account_iban` (new column) + balance, fallback desc/ref. `setRequirement` upserts `transaction_rule` matched on `(match_iban, match_desc_norm)`.

**Watch:** Task 2's `BankTransaction` constructor change breaks its sole caller (`BankStatementExtractionService`) — Task 2 Step 8 fixes it minimally; Task 5 then replaces that block. Between T2 and T5 the build stays green.
