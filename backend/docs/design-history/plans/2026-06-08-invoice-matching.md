# Invoice Matching / Reconciliation (Slice C) Implementation Plan

> **For agentic workers:** subagent-driven-development. Steps use `- [ ]` checkboxes.

**Goal:** Extract invoices (heuristic), match invoices ↔ needs-doc transactions (auto 1:1 with a date rule + manual many-to-many), and reflect matches as "Matched ✓" with completeness derived from the matches.

**Architecture:** Extend `ro.myfinance.extraction`. New `invoice` + `transaction_invoice_match` (m:n) tables (`V7`). `HeuristicInvoiceExtractor` (PDFBox text → IBAN/total/date). `ReconciliationService` gains `matchPeriod` (auto 1:1), `link`/`unlink` (manual m:n), `transactionsWithMatches`, and completeness-from-links. Matching re-runs on the existing `DocumentUploadedEvent` (AFTER_COMMIT) for invoices and after statement parse.

**Tech Stack:** Java 21 / Spring Boot 3.5.9 / JPA / Flyway / PDFBox / Testcontainers; React 18 / TS / Vite / TanStack Query / react-i18next.

## Context
- RLS everywhere; tests bind `TenantContext`, clear in `@AfterEach`. Docker NOT installed → `*IT` skip locally; verify `mvn -B -DskipTests test-compile` + `mvn -B test` (unit pass) and FE `npm run lint && npm run build`. Flyway local `out-of-order: true`.
- Existing: `bank_transaction` (+ requirement fields), `ReconciliationService` (classify/setRequirement/completenessSummary/matchRule), `BankStatementExtractionService.extract` (calls `reconciliation.classify`), `StatementExtractionListener` (`@TransactionalEventListener(AFTER_COMMIT)`, BANK_STATEMENT only), `DocumentUploadedEvent(documentId, companyId, periodMonth, type, bytes)`, `DocumentService.upload(...)` publishes it, `BankStatementController` (reads via repos), `BankStatementDtos.TransactionResponse` (has requirement fields + derived reason), `BankStatementParserRegistry.extractText(byte[])`.

---

## Task 1: V7 migration
**File:** Create `backend/src/main/resources/db/migration/V7__invoice_match.sql`
- [ ] **Step 1:**
```sql
CREATE TABLE invoice (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenant(id),
    document_id       uuid NOT NULL UNIQUE REFERENCES document(id) ON DELETE CASCADE,
    company_id        uuid NOT NULL REFERENCES company(id),
    period_month      date NOT NULL,
    supplier_name     text,
    supplier_iban     text,
    total_amount      numeric(15,2),
    invoice_date      date,
    original_filename text,
    status            text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_invoice_company_period ON invoice(tenant_id, company_id, period_month);

CREATE TABLE transaction_invoice_match (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    transaction_id uuid NOT NULL REFERENCES bank_transaction(id) ON DELETE CASCADE,
    invoice_id     uuid NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,
    source         text NOT NULL,
    created_by     uuid,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_txn_invoice UNIQUE (transaction_id, invoice_id)
);
CREATE INDEX idx_tim_transaction ON transaction_invoice_match(transaction_id);

ALTER TABLE invoice ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE transaction_invoice_match ENABLE ROW LEVEL SECURITY;
ALTER TABLE transaction_invoice_match FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON transaction_invoice_match
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON invoice TO myfinance_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON transaction_invoice_match TO myfinance_app;
    END IF;
END $$;
```
- [ ] **Step 2:** `cd backend && mvn -B -DskipTests test-compile` → BUILD SUCCESS.
- [ ] **Step 3:** Commit: `git add backend/src/main/resources/db/migration/V7__invoice_match.sql && git commit -m "feat(recon): V7 invoice + transaction_invoice_match tables"`

---

## Task 2: Entities + repositories
**Files:** Create `Invoice`, `TransactionInvoiceMatch` (domain); `InvoiceRepository`, `TransactionInvoiceMatchRepository` (persistence).

- [ ] **Step 1:** `extraction/domain/Invoice.java`
```java
package ro.myfinance.extraction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "invoice")
public class Invoice {

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

    @Column(name = "supplier_name")
    private String supplierName;

    @Column(name = "supplier_iban")
    private String supplierIban;

    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(nullable = false)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Invoice() {
    }

    public Invoice(UUID tenantId, UUID documentId, UUID companyId, LocalDate periodMonth,
                   String supplierName, String supplierIban, BigDecimal totalAmount,
                   LocalDate invoiceDate, String originalFilename, String status) {
        this.tenantId = tenantId;
        this.documentId = documentId;
        this.companyId = companyId;
        this.periodMonth = periodMonth;
        this.supplierName = supplierName;
        this.supplierIban = supplierIban;
        this.totalAmount = totalAmount;
        this.invoiceDate = invoiceDate;
        this.originalFilename = originalFilename;
        this.status = status;
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public UUID getCompanyId() { return companyId; }
    public LocalDate getPeriodMonth() { return periodMonth; }
    public String getSupplierName() { return supplierName; }
    public String getSupplierIban() { return supplierIban; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public String getOriginalFilename() { return originalFilename; }
    public String getStatus() { return status; }
}
```
- [ ] **Step 2:** `extraction/domain/TransactionInvoiceMatch.java`
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

@Entity
@Table(name = "transaction_invoice_match")
public class TransactionInvoiceMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(nullable = false)
    private String source;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransactionInvoiceMatch() {
    }

    public TransactionInvoiceMatch(UUID tenantId, UUID transactionId, UUID invoiceId, String source,
                                   UUID createdBy) {
        this.tenantId = tenantId;
        this.transactionId = transactionId;
        this.invoiceId = invoiceId;
        this.source = source;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public UUID getInvoiceId() { return invoiceId; }
    public String getSource() { return source; }
}
```
- [ ] **Step 3:** `extraction/adapter/persistence/InvoiceRepository.java`
```java
package ro.myfinance.extraction.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.extraction.domain.Invoice;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    List<Invoice> findByCompanyIdAndPeriodMonth(UUID companyId, LocalDate periodMonth);
}
```
- [ ] **Step 4:** `extraction/adapter/persistence/TransactionInvoiceMatchRepository.java`
```java
package ro.myfinance.extraction.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.extraction.domain.TransactionInvoiceMatch;

public interface TransactionInvoiceMatchRepository extends JpaRepository<TransactionInvoiceMatch, UUID> {

    List<TransactionInvoiceMatch> findByTransactionIdIn(List<UUID> transactionIds);

    boolean existsByTransactionIdAndInvoiceId(UUID transactionId, UUID invoiceId);

    void deleteByTransactionIdAndInvoiceId(UUID transactionId, UUID invoiceId);
}
```
- [ ] **Step 5:** `cd backend && mvn -B -DskipTests test-compile` → BUILD SUCCESS.
- [ ] **Step 6:** Commit: `git add backend/src/main/java/ro/myfinance/extraction/domain/Invoice.java backend/src/main/java/ro/myfinance/extraction/domain/TransactionInvoiceMatch.java backend/src/main/java/ro/myfinance/extraction/adapter/persistence/InvoiceRepository.java backend/src/main/java/ro/myfinance/extraction/adapter/persistence/TransactionInvoiceMatchRepository.java && git commit -m "feat(recon): Invoice + TransactionInvoiceMatch entities and repositories"`

---

## Task 3: Invoice extractor + unit test
**Files:** Create `InvoiceExtractor`, `ParsedInvoice` (application); `HeuristicInvoiceExtractor` (adapter/external); test.

- [ ] **Step 1:** `extraction/application/ParsedInvoice.java`
```java
package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ParsedInvoice(String supplierName, String supplierIban, BigDecimal totalAmount,
                            LocalDate invoiceDate) {
}
```
- [ ] **Step 2:** `extraction/application/InvoiceExtractor.java`
```java
package ro.myfinance.extraction.application;

/** Port: extract matching-relevant fields from an invoice PDF. Deterministic; no LLM. */
public interface InvoiceExtractor {

    ParsedInvoice extract(byte[] pdf);
}
```
- [ ] **Step 3:** `extraction/adapter/external/HeuristicInvoiceExtractor.java`
```java
package ro.myfinance.extraction.adapter.external;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.myfinance.extraction.application.InvoiceExtractor;
import ro.myfinance.extraction.application.ParsedInvoice;

/** Best-effort invoice field extraction: supplier IBAN, total, date. Never throws. */
@Component
public class HeuristicInvoiceExtractor implements InvoiceExtractor {

    private static final Logger log = LoggerFactory.getLogger(HeuristicInvoiceExtractor.class);
    private static final Pattern IBAN = Pattern.compile("\\bRO\\d{2}[A-Z0-9]{10,}\\b");
    private static final Pattern MONEY = Pattern.compile("\\d[\\d.,]*[.,]\\d{2}");
    private static final Pattern DATE = Pattern.compile("(\\d{2})[/.](\\d{2})[/.](\\d{4})");

    @Override
    public ParsedInvoice extract(byte[] pdf) {
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        } catch (IOException | RuntimeException e) {
            log.debug("Invoice text extraction failed", e);
            return new ParsedInvoice(null, null, null, null);
        }
        String iban = firstMatch(IBAN, text);
        BigDecimal total = totalAmount(text);
        LocalDate date = invoiceDate(text);
        return new ParsedInvoice(null, iban, total, date);
    }

    private BigDecimal totalAmount(String text) {
        // Prefer the money token on a line mentioning "total"; else the largest money token.
        BigDecimal labelled = null;
        BigDecimal largest = null;
        for (String line : text.split("\\R")) {
            for (Matcher m = MONEY.matcher(line); m.find(); ) {
                BigDecimal v = parseMoney(m.group());
                if (largest == null || v.compareTo(largest) > 0) {
                    largest = v;
                }
                String norm = java.text.Normalizer.normalize(line, java.text.Normalizer.Form.NFD)
                        .replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase();
                if (norm.contains("total")) {
                    if (labelled == null || v.compareTo(labelled) > 0) {
                        labelled = v;
                    }
                }
            }
        }
        return labelled != null ? labelled : largest;
    }

    private LocalDate invoiceDate(String text) {
        Matcher m = DATE.matcher(text);
        while (m.find()) {
            for (String pat : new String[] {"dd.MM.uuuu", "dd/MM/uuuu"}) {
                try {
                    return LocalDate.parse(m.group(), DateTimeFormatter.ofPattern(pat));
                } catch (RuntimeException ignored) {
                    // try next
                }
            }
        }
        return null;
    }

    private String firstMatch(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group() : null;
    }

    /** RO ("1.234,56") or EN ("1,234.56") money token. */
    private BigDecimal parseMoney(String token) {
        int lastDot = token.lastIndexOf('.');
        int lastComma = token.lastIndexOf(',');
        String n;
        if (lastDot >= 0 && lastComma >= 0) {
            n = lastDot > lastComma ? token.replace(",", "") : token.replace(".", "").replace(",", ".");
        } else if (lastComma >= 0) {
            n = (token.length() - lastComma - 1 == 2) ? token.replace(",", ".") : token.replace(",", "");
        } else {
            n = token;
        }
        return new BigDecimal(n);
    }
}
```
- [ ] **Step 4:** `backend/src/test/java/ro/myfinance/extraction/HeuristicInvoiceExtractorTest.java`
```java
package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.adapter.external.HeuristicInvoiceExtractor;
import ro.myfinance.extraction.application.ParsedInvoice;

class HeuristicInvoiceExtractorTest {

    private final HeuristicInvoiceExtractor extractor = new HeuristicInvoiceExtractor();

    private byte[] pdf(String... lines) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.setLeading(14);
                cs.newLineAtOffset(50, 720);
                for (String l : lines) {
                    cs.showText(l);
                    cs.newLine();
                }
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void extractsIbanTotalAndDate() throws Exception {
        ParsedInvoice inv = extractor.extract(pdf(
                "Factura fiscala nr 123 din 15/03/2026",
                "Furnizor ACME SRL  IBAN RO49AAAA1B31007593840000",
                "Subtotal 1000,00",
                "Total de plata 1.190,00"));
        assertThat(inv.supplierIban()).isEqualTo("RO49AAAA1B31007593840000");
        assertThat(inv.totalAmount()).isEqualByComparingTo("1190.00");
        assertThat(inv.invoiceDate()).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void garbageReturnsNulls() {
        ParsedInvoice inv = extractor.extract(new byte[]{1, 2, 3});
        assertThat(inv.supplierIban()).isNull();
        assertThat(inv.totalAmount()).isNull();
    }
}
```
- [ ] **Step 5:** `cd backend && mvn -B test` → BUILD SUCCESS; the 2 extractor tests pass.
- [ ] **Step 6:** Commit: `git add backend/src/main/java/ro/myfinance/extraction/application/ParsedInvoice.java backend/src/main/java/ro/myfinance/extraction/application/InvoiceExtractor.java backend/src/main/java/ro/myfinance/extraction/adapter/external/HeuristicInvoiceExtractor.java backend/src/test/java/ro/myfinance/extraction/HeuristicInvoiceExtractorTest.java && git commit -m "feat(recon): heuristic invoice extractor (IBAN/total/date) + unit test"`

---

## Task 4: Event filename + InvoiceExtractionService + listener routing + match-after-parse
**Files:** Modify `intake/application/DocumentUploadedEvent.java`, `intake/application/DocumentService.java`, `extraction/application/StatementExtractionListener.java`, `extraction/application/BankStatementExtractionService.java`; create `extraction/application/InvoiceExtractionService.java`. (Task 5 adds `matchPeriod`/etc to `ReconciliationService`; this task references `reconciliation.matchPeriod(...)` — implement Task 5 FIRST if compiling between tasks, OR accept a temporary unresolved symbol. To stay green, do Task 5 before this task's compile. The committed order below assumes Task 5 is already merged; if executing strictly in number order, build Task 4 + 5 together before compiling.)

NOTE TO IMPLEMENTER: implement Task 5 (ReconciliationService additions) in the SAME working session before compiling Task 4, since they reference each other. Commit Task 5 first, then Task 4. (Tasks 4 and 5 are co-dependent.)

- [ ] **Step 1:** `intake/application/DocumentUploadedEvent.java` — add `filename`:
```java
package ro.myfinance.intake.application;

import java.time.LocalDate;
import java.util.UUID;
import ro.myfinance.intake.domain.DocumentType;

/** Published after a document is uploaded; extraction listens (BANK_STATEMENT, INVOICE). */
public record DocumentUploadedEvent(UUID documentId, UUID companyId, LocalDate periodMonth,
                                    DocumentType type, String filename, byte[] bytes) {
}
```
- [ ] **Step 2:** In `DocumentService.upload`, update the publish call to pass the filename (it has the `filename` parameter):
```java
        events.publishEvent(new DocumentUploadedEvent(saved.getId(), companyId, period, type, filename, bytes));
```
- [ ] **Step 3:** `extraction/application/InvoiceExtractionService.java`
```java
package ro.myfinance.extraction.application;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.extraction.adapter.persistence.InvoiceRepository;
import ro.myfinance.extraction.domain.Invoice;

/** Extracts an uploaded invoice and triggers (re)matching for its company+period. */
@Service
@Transactional
public class InvoiceExtractionService {

    private final InvoiceExtractor extractor;
    private final InvoiceRepository invoices;
    private final ReconciliationService reconciliation;

    public InvoiceExtractionService(InvoiceExtractor extractor, InvoiceRepository invoices,
                                    ReconciliationService reconciliation) {
        this.extractor = extractor;
        this.invoices = invoices;
        this.reconciliation = reconciliation;
    }

    public void process(UUID documentId, UUID companyId, LocalDate periodMonth, String filename, byte[] bytes) {
        UUID tenantId = TenantContext.tenantId().orElseThrow(() -> new IllegalStateException("No tenant bound"));
        ParsedInvoice p = extractor.extract(bytes);
        String status = (p.supplierIban() != null && p.totalAmount() != null) ? "EXTRACTED" : "NEEDS_REVIEW";
        invoices.save(new Invoice(tenantId, documentId, companyId, periodMonth.withDayOfMonth(1),
                p.supplierName(), p.supplierIban(), p.totalAmount(), p.invoiceDate(), filename, status));
        reconciliation.matchPeriod(companyId, periodMonth.withDayOfMonth(1));
    }
}
```
- [ ] **Step 4:** Replace `extraction/application/StatementExtractionListener.java` to route both types:
```java
package ro.myfinance.extraction.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ro.myfinance.intake.application.DocumentUploadedEvent;
import ro.myfinance.intake.domain.DocumentType;

/** After a document upload COMMITS, extract + match in its own transaction. Failures never break upload. */
@Component
public class StatementExtractionListener {

    private static final Logger log = LoggerFactory.getLogger(StatementExtractionListener.class);

    private final BankStatementExtractionService statements;
    private final InvoiceExtractionService invoices;

    public StatementExtractionListener(BankStatementExtractionService statements,
                                       InvoiceExtractionService invoices) {
        this.statements = statements;
        this.invoices = invoices;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent e) {
        try {
            if (e.type() == DocumentType.BANK_STATEMENT) {
                statements.extract(e.documentId(), e.companyId(), e.periodMonth(), e.bytes());
            } else if (e.type() == DocumentType.INVOICE) {
                invoices.process(e.documentId(), e.companyId(), e.periodMonth(), e.filename(), e.bytes());
            }
        } catch (RuntimeException ex) {
            log.warn("Extraction failed for document {} ({})", e.documentId(), e.type(), ex);
        }
    }
}
```
- [ ] **Step 5:** In `BankStatementExtractionService.extract`, after `reconciliation.classify(statement.getId());` add:
```java
        reconciliation.matchPeriod(companyId, periodMonth);
```
- [ ] **Step 6:** Compile after Task 5 is in place: `cd backend && mvn -B -DskipTests test-compile` → BUILD SUCCESS.
- [ ] **Step 7:** Commit: `git add backend/src/main/java/ro/myfinance/intake/application/DocumentUploadedEvent.java backend/src/main/java/ro/myfinance/intake/application/DocumentService.java backend/src/main/java/ro/myfinance/extraction/application/InvoiceExtractionService.java backend/src/main/java/ro/myfinance/extraction/application/StatementExtractionListener.java backend/src/main/java/ro/myfinance/extraction/application/BankStatementExtractionService.java && git commit -m "feat(recon): invoice extraction trigger + match-after-parse (event routing)"`

---

## Task 5: ReconciliationService — matchPeriod, link/unlink, transactionsWithMatches, completeness-from-links (+ IT)
**Files:** Modify `extraction/application/ReconciliationService.java`; modify `extraction/ReconciliationServiceIT.java`.

- [ ] **Step 1:** Add to `ReconciliationService` (read the file; add fields `InvoiceRepository invoices`, `TransactionInvoiceMatchRepository matches` to the constructor + assignments; add `ROUND` constant `BigDecimal TOLERANCE = new BigDecimal("0.01")`). Add these methods and records; UPDATE `completenessSummary` to use links.

Add imports: `java.math.BigDecimal`, `ro.myfinance.extraction.adapter.persistence.InvoiceRepository`, `ro.myfinance.extraction.adapter.persistence.TransactionInvoiceMatchRepository`, `ro.myfinance.extraction.domain.Invoice`, `ro.myfinance.extraction.domain.TransactionInvoiceMatch`, `ro.myfinance.common.web.IllegalArgument...` (use `IllegalArgumentException`).

New records (nested in ReconciliationService):
```java
    public record MatchedInvoiceView(UUID invoiceId, UUID documentId, String filename,
                                     java.math.BigDecimal totalAmount, java.time.LocalDate invoiceDate,
                                     String supplierName) {
    }

    public record TxnWithMatches(BankTransaction txn, java.util.List<MatchedInvoiceView> invoices) {
    }
```

`matchPeriod` (auto 1:1, date rule):
```java
    public void matchPeriod(UUID companyId, java.time.LocalDate periodMonth) {
        java.time.LocalDate period = periodMonth.withDayOfMonth(1);
        List<UUID> stmtIds = statements.findByCompanyIdAndPeriodMonth(companyId, period)
                .stream().map(BankStatement::getId).toList();
        if (stmtIds.isEmpty()) {
            return;
        }
        List<BankTransaction> txns = transactions.findByStatementIdInOrderByTxnDateDesc(stmtIds);
        List<UUID> txnIds = txns.stream().map(BankTransaction::getId).toList();
        java.util.Set<UUID> matchedTxnIds = new java.util.HashSet<>();
        java.util.Set<UUID> usedInvoiceIds = new java.util.HashSet<>();
        for (TransactionInvoiceMatch m : matches.findByTransactionIdIn(txnIds)) {
            matchedTxnIds.add(m.getTransactionId());
            usedInvoiceIds.add(m.getInvoiceId());
        }
        List<Invoice> periodInvoices = invoices.findByCompanyIdAndPeriodMonth(companyId, period).stream()
                .filter(i -> i.getSupplierIban() != null && i.getTotalAmount() != null
                        && !usedInvoiceIds.contains(i.getId()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        UUID tenantId = TenantContext.tenantId().orElseThrow();
        for (BankTransaction t : txns) {
            if (!t.isRequiresDocument() || matchedTxnIds.contains(t.getId()) || t.getPartnerIban() == null) {
                continue;
            }
            Invoice hit = null;
            for (Invoice inv : periodInvoices) {
                boolean ibanOk = inv.getSupplierIban().equals(t.getPartnerIban());
                boolean amtOk = inv.getTotalAmount().abs().subtract(t.getAmount().abs()).abs()
                        .compareTo(TOLERANCE) <= 0;
                boolean dateOk = inv.getInvoiceDate() == null || !t.getTxnDate().isBefore(inv.getInvoiceDate());
                if (ibanOk && amtOk && dateOk) {
                    hit = inv;
                    break;
                }
            }
            if (hit != null) {
                matches.save(new TransactionInvoiceMatch(tenantId, t.getId(), hit.getId(), "AUTO", null));
                matchedTxnIds.add(t.getId());
                periodInvoices.remove(hit);
            }
        }
    }
```

`link` / `unlink` (manual, m:n, date rule, company-scoped):
```java
    public void link(UUID companyId, UUID txnId, UUID invoiceId) {
        BankTransaction t = transactions.findById(txnId)
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + txnId));
        Invoice inv = invoices.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + invoiceId));
        if (!t.getCompanyId().equals(companyId) || !inv.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Not found in company " + companyId);
        }
        if (inv.getInvoiceDate() != null && t.getTxnDate().isBefore(inv.getInvoiceDate())) {
            throw new IllegalArgumentException("Transaction date is before the invoice date");
        }
        if (!matches.existsByTransactionIdAndInvoiceId(txnId, invoiceId)) {
            UUID tenantId = TenantContext.tenantId().orElseThrow();
            UUID userId = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
            matches.save(new TransactionInvoiceMatch(tenantId, txnId, invoiceId, "MANUAL", userId));
            audit.record("TXN_INVOICE_LINKED", "bank_transaction", txnId);
        }
    }

    public void unlink(UUID companyId, UUID txnId, UUID invoiceId) {
        matches.deleteByTransactionIdAndInvoiceId(txnId, invoiceId);
        audit.record("TXN_INVOICE_UNLINKED", "bank_transaction", txnId);
    }
```

`transactionsWithMatches`:
```java
    @Transactional(readOnly = true)
    public List<TxnWithMatches> transactionsWithMatches(UUID companyId, java.time.LocalDate periodMonth) {
        java.time.LocalDate period = periodMonth.withDayOfMonth(1);
        List<UUID> stmtIds = statements.findByCompanyIdAndPeriodMonth(companyId, period)
                .stream().map(BankStatement::getId).toList();
        if (stmtIds.isEmpty()) {
            return List.of();
        }
        List<BankTransaction> txns = transactions.findByStatementIdInOrderByTxnDateDesc(stmtIds);
        List<UUID> txnIds = txns.stream().map(BankTransaction::getId).toList();
        List<TransactionInvoiceMatch> links = matches.findByTransactionIdIn(txnIds);
        java.util.Map<UUID, Invoice> invById = new java.util.HashMap<>();
        for (Invoice i : invoices.findAllById(links.stream().map(TransactionInvoiceMatch::getInvoiceId).toList())) {
            invById.put(i.getId(), i);
        }
        java.util.Map<UUID, List<MatchedInvoiceView>> byTxn = new java.util.HashMap<>();
        for (TransactionInvoiceMatch m : links) {
            Invoice i = invById.get(m.getInvoiceId());
            if (i != null) {
                byTxn.computeIfAbsent(m.getTransactionId(), k -> new java.util.ArrayList<>())
                        .add(new MatchedInvoiceView(i.getId(), i.getDocumentId(), i.getOriginalFilename(),
                                i.getTotalAmount(), i.getInvoiceDate(), i.getSupplierName()));
            }
        }
        return txns.stream()
                .map(t -> new TxnWithMatches(t, byTxn.getOrDefault(t.getId(), List.of())))
                .toList();
    }
```

UPDATE `completenessSummary` — replace the `missing` computation to use links:
```java
            List<UUID> txnIds = transactions.findByStatementIdInOrderByTxnDateDesc(stmtIds)
                    .stream().filter(BankTransaction::isRequiresDocument).map(BankTransaction::getId).toList();
            boolean missing;
            if (txnIds.isEmpty()) {
                missing = false;
            } else {
                java.util.Set<UUID> matchedIds = matches.findByTransactionIdIn(txnIds).stream()
                        .map(TransactionInvoiceMatch::getTransactionId).collect(java.util.stream.Collectors.toSet());
                missing = txnIds.stream().anyMatch(id -> !matchedIds.contains(id));
            }
            out.add(new CompanyCompleteness(e.getKey(), missing ? Completeness.PARTIAL : Completeness.COMPLETE));
```
(Replace the existing `boolean missing = ...anyMatch(... getMatchedDocumentId() == null)` block accordingly. Add `InvoiceRepository invoices` + `TransactionInvoiceMatchRepository matches` to the constructor.)

- [ ] **Step 2:** Extend `ReconciliationServiceIT` — add an autowired `InvoiceRepository`/`TransactionInvoiceMatchRepository` and these tests (append; keep existing). Helper to create an invoice document + invoice row via the upload path or directly. Simplest: insert an `invoice` row via the service path is awkward (needs a PDF); instead drive through `documents.upload` of an INVOICE PDF whose text yields the IBAN/amount, OR insert the invoice row directly with the repo under the bound tenant. Use direct repo insert for determinism:
```java
    @Autowired ro.myfinance.extraction.adapter.persistence.InvoiceRepository invoiceRepo;
    @Autowired ro.myfinance.extraction.adapter.persistence.TransactionInvoiceMatchRepository matchRepo;

    private UUID seedInvoice(UUID companyId, String iban, String amount, LocalDate date) {
        UUID tenantId = TENANT_A; // bound tenant in the active test
        // a document row is required (FK). Insert a minimal INVOICE document via jdbc, then an invoice row.
        UUID docId = UUID.randomUUID();
        jdbc.update("insert into document(id, tenant_id, company_id, period_month, type, source, status, "
                + "original_filename, content_type, size_bytes, storage_key) "
                + "values (?,?,?,?, 'INVOICE','EMPLOYEE','UPLOADED','inv.pdf','application/pdf',1,'k/"+docId+"')",
                docId, tenantId, companyId, java.sql.Date.valueOf(LocalDate.of(2026, 6, 1)));
        ro.myfinance.extraction.domain.Invoice inv = invoiceRepo.save(new ro.myfinance.extraction.domain.Invoice(
                tenantId, docId, companyId, LocalDate.of(2026, 6, 1), "ACME", iban,
                new java.math.BigDecimal(amount), date, "inv.pdf", "EXTRACTED"));
        return inv.getId();
    }
```
Note: this helper assumes the active tenant is `TENANT_A`; call it only while `TENANT_A` is bound. Add tests:
```java
    @Test
    void autoMatchesInvoiceToSupplierTransaction() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        BankTransaction supplier = companyTxns(companyId).stream()
                .filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();
        // SELGROS supplier txn from the stub: partnerIban RO21SUPP, amount -200.00, date 2026-06-03
        seedInvoice(companyId, "RO21SUPP", "200.00", LocalDate.of(2026, 6, 1));
        reconciliation.matchPeriod(companyId, LocalDate.of(2026, 6, 1));

        assertThat(matchRepo.findByTransactionIdIn(List.of(supplier.getId()))).hasSize(1);
        var summary = reconciliation.completenessSummary(LocalDate.of(2026, 6, 1));
        assertThat(summary).anySatisfy(c -> {
            assertThat(c.companyId()).isEqualTo(companyId);
            assertThat(c.completeness()).isEqualTo(ReconciliationService.Completeness.COMPLETE);
        });
    }

    @Test
    void doesNotMatchInvoiceDatedAfterTransaction() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        BankTransaction supplier = companyTxns(companyId).stream()
                .filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();
        seedInvoice(companyId, "RO21SUPP", "200.00", LocalDate.of(2026, 6, 30)); // after the txn (06-03)
        reconciliation.matchPeriod(companyId, LocalDate.of(2026, 6, 1));
        assertThat(matchRepo.findByTransactionIdIn(List.of(supplier.getId()))).isEmpty();
    }

    @Test
    void manualLinkRejectsTxnBeforeInvoice() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        BankTransaction supplier = companyTxns(companyId).stream()
                .filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();
        UUID invId = seedInvoice(companyId, "RO21SUPP", "999.00", LocalDate.of(2026, 6, 30));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                reconciliation.link(companyId, supplier.getId(), invId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manualLinkAndUnlink() throws Exception {
        UUID companyId = asTenantWithCompany(TENANT_A);
        documents.upload(companyId, LocalDate.of(2026, 6, 1), "extras.pdf", "application/pdf", pdf("RECONSTUB"));
        BankTransaction supplier = companyTxns(companyId).stream()
                .filter(t -> "SELGROS".equals(t.getPartnerName())).findFirst().orElseThrow();
        UUID invId = seedInvoice(companyId, "RO21SUPP", "200.00", LocalDate.of(2026, 6, 1));
        reconciliation.link(companyId, supplier.getId(), invId);
        assertThat(matchRepo.findByTransactionIdIn(List.of(supplier.getId()))).hasSize(1);
        reconciliation.unlink(companyId, supplier.getId(), invId);
        assertThat(matchRepo.findByTransactionIdIn(List.of(supplier.getId()))).isEmpty();
    }
```
Add imports used (`java.time.LocalDate` etc. already present). `IllegalArgumentException`/`NotFoundException` resolve.
- [ ] **Step 3:** `cd backend && mvn -B -DskipTests test-compile` → clean; `mvn -B test` → BUILD SUCCESS (unit tests pass; ITs skip).
- [ ] **Step 4:** Commit (this + Task 4 are co-dependent — commit Task 5 first):
```bash
git add backend/src/main/java/ro/myfinance/extraction/application/ReconciliationService.java backend/src/test/java/ro/myfinance/extraction/ReconciliationServiceIT.java
git commit -m "feat(recon): matchPeriod (auto 1:1 + date rule), manual link/unlink, matches read, completeness-from-links"
```

---

## Task 6: API — enriched transactions, invoices list, match/unmatch
**Files:** Modify `extraction/adapter/web/BankStatementDtos.java`, `extraction/adapter/web/BankStatementController.java`, `extraction/adapter/web/ReconciliationController.java`; create `extraction/adapter/web/InvoiceController.java`.

- [ ] **Step 1:** In `BankStatementDtos`, add a matched-invoice DTO and extend `TransactionResponse`. Add:
```java
    public record MatchedInvoiceResponse(UUID invoiceId, UUID documentId, String filename,
                                         BigDecimal totalAmount, LocalDate invoiceDate, String supplierName) {
    }

    public record InvoiceResponse(UUID id, UUID documentId, String filename, String supplierName,
                                  String supplierIban, BigDecimal totalAmount, LocalDate invoiceDate,
                                  String status) {
        public static InvoiceResponse from(ro.myfinance.extraction.domain.Invoice i) {
            return new InvoiceResponse(i.getId(), i.getDocumentId(), i.getOriginalFilename(),
                    i.getSupplierName(), i.getSupplierIban(), i.getTotalAmount(), i.getInvoiceDate(), i.getStatus());
        }
    }

    public record MatchRequest(UUID invoiceId) {
    }
```
Replace `TransactionResponse` with one that also carries `matched` + `matchedInvoices`, built from a txn + the matched-invoice views:
```java
    public record TransactionResponse(UUID id, UUID statementId, LocalDate txnDate, BigDecimal amount,
                                      String direction, String partnerName, String partnerIban,
                                      String description, BigDecimal balanceAfter, boolean requiresDocument,
                                      boolean matched, String category, String decisionSource, String reason,
                                      java.util.List<MatchedInvoiceResponse> matchedInvoices) {
        public static TransactionResponse from(ro.myfinance.extraction.application.ReconciliationService.TxnWithMatches tw) {
            BankTransaction t = tw.txn();
            java.util.List<MatchedInvoiceResponse> mi = tw.invoices().stream()
                    .map(v -> new MatchedInvoiceResponse(v.invoiceId(), v.documentId(), v.filename(),
                            v.totalAmount(), v.invoiceDate(), v.supplierName()))
                    .toList();
            return new TransactionResponse(t.getId(), t.getStatementId(), t.getTxnDate(), t.getAmount(),
                    t.getDirection().name(), t.getPartnerName(), t.getPartnerIban(), t.getDescription(),
                    t.getBalanceAfter(), t.isRequiresDocument(), !mi.isEmpty(),
                    t.getCategory() == null ? null : t.getCategory().name(),
                    t.getDecisionSource() == null ? null : t.getDecisionSource().name(), reason(t), mi);
        }

        private static String reason(BankTransaction t) {
            if (t.getDecisionSource() == ro.myfinance.extraction.domain.DecisionSource.ACCOUNTANT_SET) {
                return t.getOverrideReason() != null ? t.getOverrideReason() : "Set by accountant — learned rule saved";
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
Keep `StatementResponse` and `SetRequirementRequest`. Ensure imports: `java.math.BigDecimal`, `java.time.LocalDate`, `java.util.UUID`, `ro.myfinance.extraction.domain.BankTransaction`.
- [ ] **Step 2:** In `BankStatementController`, change the `transactions(...)` method to use `ReconciliationService.transactionsWithMatches`. Inject `ReconciliationService` (add constructor param + field `recon`). Replace the method body:
```java
    @GetMapping("/bank-transactions")
    public List<TransactionResponse> transactions(@PathVariable UUID companyId,
                                                  @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return recon.transactionsWithMatches(companyId, period).stream()
                .map(TransactionResponse::from).toList();
    }
```
(Remove the now-unused `BankTransactionRepository`-based transaction query if it leaves an unused field — keep `statements` for the `/bank-statements` endpoint; drop `transactions` repo field if unused. Read the file and keep it compiling.)
- [ ] **Step 3:** Add match/unmatch to `ReconciliationController`:
```java
    @PostMapping("/api/v1/companies/{companyId}/bank-transactions/{txnId}/matches")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void link(@PathVariable UUID companyId, @PathVariable UUID txnId,
                     @RequestBody BankStatementDtos.MatchRequest r) {
        service.link(companyId, txnId, r.invoiceId());
    }

    @DeleteMapping("/api/v1/companies/{companyId}/bank-transactions/{txnId}/matches/{invoiceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@PathVariable UUID companyId, @PathVariable UUID txnId, @PathVariable UUID invoiceId) {
        service.unlink(companyId, txnId, invoiceId);
    }
```
Add imports: `org.springframework.web.bind.annotation.PostMapping`, `DeleteMapping`, `RequestBody`, `ResponseStatus`, `org.springframework.http.HttpStatus`.
- [ ] **Step 4:** Create `extraction/adapter/web/InvoiceController.java`:
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
import ro.myfinance.extraction.adapter.persistence.InvoiceRepository;
import ro.myfinance.extraction.adapter.web.BankStatementDtos.InvoiceResponse;

/** Invoices for a company/period (manual-link candidates). Firm staff only. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/invoices")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class InvoiceController {

    private final InvoiceRepository invoices;

    public InvoiceController(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    @GetMapping
    public List<InvoiceResponse> list(@PathVariable UUID companyId,
                                      @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return invoices.findByCompanyIdAndPeriodMonth(companyId, period).stream()
                .map(InvoiceResponse::from).toList();
    }
}
```
- [ ] **Step 5:** `cd backend && mvn -B test` → BUILD SUCCESS.
- [ ] **Step 6:** Commit: `git add backend/src/main/java/ro/myfinance/extraction/adapter/web/ && git commit -m "feat(recon): enriched transactions w/ matches, invoices list, match/unmatch endpoints"`

---

## Task 7: Frontend API + types + i18n
**Files:** Modify `frontend/src/api/bank.ts`, `frontend/src/i18n.ts`.

- [ ] **Step 1:** In `frontend/src/api/bank.ts`: extend `BankTransaction` with `matched: boolean` and `matchedInvoices: MatchedInvoice[]`; add `MatchedInvoice` + `Invoice` interfaces; add `bankApi.match`/`unmatch` and `invoicesApi.list`. Append/extend (keep existing exports):
```ts
export interface MatchedInvoice {
  invoiceId: string;
  documentId: string;
  filename: string | null;
  totalAmount: number | null;
  invoiceDate: string | null;
  supplierName: string | null;
}

export interface Invoice {
  id: string;
  documentId: string;
  filename: string | null;
  supplierName: string | null;
  supplierIban: string | null;
  totalAmount: number | null;
  invoiceDate: string | null;
  status: string;
}
```
Add `matched: boolean;` and `matchedInvoices: MatchedInvoice[];` to the `BankTransaction` interface. Add to `bankApi`:
```ts
  match: (companyId: string, txnId: string, invoiceId: string) =>
    api<void>(`/api/v1/companies/${companyId}/bank-transactions/${txnId}/matches`, {
      method: "POST",
      body: JSON.stringify({ invoiceId }),
    }),
  unmatch: (companyId: string, txnId: string, invoiceId: string) =>
    api<void>(`/api/v1/companies/${companyId}/bank-transactions/${txnId}/matches/${invoiceId}`, {
      method: "DELETE",
    }),
```
Add a new export:
```ts
export const invoicesApi = {
  list: (companyId: string, period: string) =>
    api<Invoice[]>(`/api/v1/companies/${companyId}/invoices?period=${period}`),
};
```
- [ ] **Step 2:** Add i18n keys to `frontend/src/i18n.ts` (`ro` then `en`), after the `recon.*` block:
```ts
      "recon.matched": "Asociat",
      "recon.link": "Asociază",
      "recon.unlink": "Elimină",
      "recon.pickInvoice": "Alege factura",
      "recon.noInvoices": "Nicio factură eligibilă",
```
EN:
```ts
      "recon.matched": "Matched",
      "recon.link": "Link",
      "recon.unlink": "Unlink",
      "recon.pickInvoice": "Choose invoice",
      "recon.noInvoices": "No eligible invoices",
```
- [ ] **Step 3:** `cd frontend && npx tsc -b` → no errors.
- [ ] **Step 4:** Commit: `git add frontend/src/api/bank.ts frontend/src/i18n.ts && git commit -m "feat(fe): invoice/match API + types + i18n"`

---

## Task 8: Recon modal — matched display + link picker + unlink
**Files:** Modify `frontend/src/components/ReconModal.tsx`.

- [ ] **Step 1:** Read the file. Add: an `invoices` query (`invoicesApi.list`), `match`/`unmatch` mutations, and a per-row `linkingTxn` state for the picker. In the **Document column** cell, render:
  - if `tx.matched`: green "✓ {t('recon.matched')}" + each `tx.matchedInvoices` filename with an unlink ✕ (calls `unmatch`).
  - else if `tx.requiresDocument`: keep "⚠ Needs doc" + a small **Link** button that toggles a picker listing `invoices` whose `invoiceDate <= tx.txnDate` (the date rule), each clickable → `match(tx.id, inv.id)`.
  - else: "Not needed".
  Wire mutations to invalidate `["bank-txns", companyId, period]` (and `["recon-summary", period]`).

Concretely, add near the other hooks:
```tsx
  const invoices = useQuery({ queryKey: ["invoices", companyId, period], queryFn: () => invoicesApi.list(companyId, period) });
  const [linkingTxn, setLinkingTxn] = useState<string | null>(null);
  const match = useMutation({
    mutationFn: ({ txnId, invoiceId }: { txnId: string; invoiceId: string }) => bankApi.match(companyId, txnId, invoiceId),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ["bank-txns", companyId, period] }); void qc.invalidateQueries({ queryKey: ["recon-summary", period] }); setLinkingTxn(null); },
  });
  const unmatch = useMutation({
    mutationFn: ({ txnId, invoiceId }: { txnId: string; invoiceId: string }) => bankApi.unmatch(companyId, txnId, invoiceId),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ["bank-txns", companyId, period] }); void qc.invalidateQueries({ queryKey: ["recon-summary", period] }); },
  });
```
Replace the Document `<td>` cell with:
```tsx
                <td style={{ padding: 8, whiteSpace: "nowrap", fontSize: 12 }}>
                  {tx.matched ? (
                    <div>
                      {tx.matchedInvoices.map((mi) => (
                        <div key={mi.invoiceId} style={{ color: "#166534" }}>
                          ✓ {mi.filename ?? "factura"}{" "}
                          <button onClick={() => unmatch.mutate({ txnId: tx.id, invoiceId: mi.invoiceId })}
                            style={{ border: "none", background: "none", color: "#991b1b", cursor: "pointer" }}>✕</button>
                        </div>
                      ))}
                    </div>
                  ) : tx.requiresDocument ? (
                    <div>
                      <span style={{ color: "#991b1b" }}>⚠ {t("recon.needsDoc")}</span>{" "}
                      <button onClick={() => setLinkingTxn(linkingTxn === tx.id ? null : tx.id)}>{t("recon.link")}</button>
                      {linkingTxn === tx.id && (
                        <div style={{ marginTop: 4, border: "1px solid var(--border)", borderRadius: 8, padding: 6, background: "#fff" }}>
                          {(invoices.data ?? []).filter((inv) => !inv.invoiceDate || inv.invoiceDate <= tx.txnDate).length === 0 && (
                            <div style={{ color: "var(--text-muted)" }}>{t("recon.noInvoices")}</div>
                          )}
                          {(invoices.data ?? []).filter((inv) => !inv.invoiceDate || inv.invoiceDate <= tx.txnDate).map((inv) => (
                            <div key={inv.id} onClick={() => match.mutate({ txnId: tx.id, invoiceId: inv.id })}
                              style={{ cursor: "pointer", padding: "3px 4px" }}>
                              {inv.filename ?? "factura"} · {inv.totalAmount != null ? fmt(inv.totalAmount) : "—"}
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  ) : (
                    <span style={{ color: "var(--text-muted)" }}>{t("recon.notNeeded")}</span>
                  )}
                </td>
```
Add imports: `invoicesApi` from `../api/bank`. The string date comparison works because both are ISO `yyyy-MM-dd`.
- [ ] **Step 2:** `cd frontend && npm run lint && npm run build` → both succeed.
- [ ] **Step 3:** Commit: `git add frontend/src/components/ReconModal.tsx && git commit -m "feat(fe): recon modal — matched invoices, link picker (date rule), unlink"`

---

## Task 9: Final verification
- [ ] **Step 1:** `cd backend && mvn -B test` → BUILD SUCCESS (unit pass; ITs skip).
- [ ] **Step 2:** `cd frontend && npm run lint && npm run build` → both clean.
- [ ] **Step 3:** Commit any fixes.

---

## Self-review
**Spec coverage:** V7 (T1) · entities/repos (T2) · invoice extractor + test (T3) · event filename + invoice extraction trigger + match-after-parse + listener routing (T4) · matchPeriod(auto 1:1 + date rule) + manual m:n link/unlink + transactionsWithMatches + completeness-from-links + IT (T5) · enriched transactions + invoices list + match/unmatch endpoints (T6) · FE api/types/i18n (T7) · recon modal matched/link/unlink with date-rule filtering (T8) · verify (T9). Date rule enforced in matchPeriod + link + FE picker filter. M:N via join table; auto 1:1 only. ✓

**Co-dependency:** Tasks 4 & 5 reference each other (listener/extract → matchPeriod; InvoiceExtractionService → reconciliation) — implement both before compiling; commit Task 5 then Task 4.

**Type consistency:** `ParsedInvoice` ↔ extractor ↔ `Invoice` row. `TxnWithMatches`/`MatchedInvoiceView` ↔ `TransactionResponse.matchedInvoices` ↔ `bankApi.BankTransaction.matchedInvoices` ↔ ReconModal. `MatchRequest{invoiceId}` ↔ POST matches ↔ `bankApi.match`. Completeness now reads `transaction_invoice_match`.
