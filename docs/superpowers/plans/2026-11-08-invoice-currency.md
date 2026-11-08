# Invoice & Transaction Currency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give invoices and bank transactions an explicit currency, stop the matcher suggesting pairs in different currencies, and show the currency on screen.

**Architecture:** One additive Flyway migration adds `currency char(3) NOT NULL DEFAULT 'RON'` to `invoice` and `bank_transaction`, which backfills existing rows in the same statement. The invoice extractor learns to read the currency from the document; the statement parsers pass through the currency they already have. `MatchSuggester` gains a currency-equality guard on both of its matching paths. Manual linking is untouched. The frontend gets one shared `formatMoney` helper that prints the currency only when it is not RON.

**Tech Stack:** Java 21 / Spring Boot 3, Flyway, JPA, JUnit 5 + AssertJ + Mockito, PDFBox; React 18 + TypeScript strict, Vitest.

**Spec:** `docs/superpowers/specs/2026-11-08-invoice-currency-design.md`

## Global Constraints

- Currency is stored as `char(3)`, `NOT NULL`, `DEFAULT 'RON'`, with `CHECK (currency ~ '^[A-Z]{3}$')`.
- `RON` is the default everywhere a currency cannot be determined. Never null, never empty.
- No FX rates and no conversion are introduced anywhere in this plan.
- Receipt currency stays out of scope: the receipt paths pass `"RON"` even though `ParsedReceipt` carries a currency. Spec, "Out of scope".
- Manual linking (`ReconciliationService.match()`) must keep working across currencies. The guard applies to *suggestions* only.
- The frontend prints the currency **only when it is not RON**.
- Backend: Java 21, no new dependencies, no iText. Frontend: TypeScript `strict`, zero `any`/`@ts-ignore`.
- Run backend tests with `mvn -o -f backend/pom.xml test -Dtest=<Class>`; frontend with `cd frontend && npm test -- --run`.
- **No new cross-tenant isolation test is required.** The backend golden rules demand one for every new
  data-access path; this plan adds only columns to existing tenant tables whose RLS policies already
  cover them, and introduces no new query path. Say so in the PR so its absence reads as deliberate.

## Review Focus

- **Subset-sum path mixing currencies** — a EUR invoice and a RON invoice that together equal a RON transaction must not be suggested. Easy to guard the exact path and forget this one. Task 5.
- **Manual linking across currencies must still succeed** — a guard placed in `ReconciliationService` instead of `MatchSuggester` would silently forbid what the spec explicitly allows. Task 5.
- **Existing invoices after the migration read `RON`**, not null or empty, so nothing downstream sees a blank currency. Task 1.
- **A RON amount must not gain a currency suffix** — this is the regression that would annoy every RON-only client on every screen. Task 6.
- **`formatMoney` with a null amount** renders `—`, not `NaN` or `null RON`. Task 6.

---

### Task 1: Migration and entity columns

**Files:**
- Create: `backend/src/main/resources/db/migration/V71__invoice_transaction_currency.sql`
- Modify: `backend/src/main/java/ro/myfinance/extraction/domain/Invoice.java` (add field, getter/setter; extend constructor at line 72 and `updateExtraction` at line 88)
- Modify: `backend/src/main/java/ro/myfinance/extraction/domain/BankTransaction.java` (add field, getter; extend constructor at line 77)
- Test: `backend/src/test/java/ro/myfinance/extraction/CurrencyDefaultTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `Invoice(UUID tenantId, UUID documentId, UUID companyId, LocalDate periodMonth, String supplierName, String supplierIban, BigDecimal totalAmount, LocalDate invoiceDate, String originalFilename, String status, String currency)` — currency appended last.
  - `Invoice.updateExtraction(String supplierName, String supplierIban, BigDecimal totalAmount, LocalDate invoiceDate, String originalFilename, String status, String currency)` — currency appended last.
  - `Invoice.getCurrency()` / `setCurrency(String)`.
  - `BankTransaction(UUID tenantId, UUID companyId, UUID statementId, String accountIban, LocalDate txnDate, BigDecimal amount, TxnDirection direction, String partnerName, String partnerIban, String description, String ref, BigDecimal balanceAfter, String currency)` — currency appended last.
  - `BankTransaction.getCurrency()`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/ro/myfinance/extraction/CurrencyDefaultTest.java`:

```java
package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.domain.BankTransaction;
import ro.myfinance.extraction.domain.Invoice;
import ro.myfinance.extraction.domain.TxnDirection;

/**
 * The column is NOT NULL, so a row must never reach the database with a null currency. Every existing
 * row is backfilled to RON by the migration's DEFAULT; these assert the Java side agrees, so a caller
 * that passes null gets RON rather than a constraint violation at runtime.
 */
class CurrencyDefaultTest {

    @Test
    void invoiceWithoutACurrencyIsRon() {
        Invoice inv = new Invoice(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 9, 1), "ACME SRL", null, new BigDecimal("100.00"),
                LocalDate.of(2026, 9, 10), "f.pdf", "EXTRACTED", null);
        assertThat(inv.getCurrency()).isEqualTo("RON");
    }

    @Test
    void invoiceKeepsAnExplicitCurrency() {
        Invoice inv = new Invoice(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 9, 1), "Bolt", null, new BigDecimal("59.90"),
                LocalDate.of(2026, 9, 24), "bolt.pdf", "EXTRACTED", "EUR");
        assertThat(inv.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void reExtractionCanChangeTheCurrency() {
        Invoice inv = new Invoice(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 9, 1), "Bolt", null, new BigDecimal("59.90"),
                LocalDate.of(2026, 9, 24), "bolt.pdf", "EXTRACTED", "RON");
        inv.updateExtraction("Bolt", null, new BigDecimal("59.90"),
                LocalDate.of(2026, 9, 24), "bolt.pdf", "EXTRACTED", "EUR");
        assertThat(inv.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void transactionWithoutACurrencyIsRon() {
        BankTransaction t = new BankTransaction(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "RO49AAAA1B31007593840000", LocalDate.of(2026, 9, 4), new BigDecimal("-59.90"),
                TxnDirection.DEBIT, "Bolt", null, "ride", "ref", null, null);
        assertThat(t.getCurrency()).isEqualTo("RON");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -f backend/pom.xml test -Dtest=CurrencyDefaultTest`
Expected: FAIL to compile — the constructors do not take a currency and `getCurrency()` does not exist.

- [ ] **Step 3: Write the migration**

Create `backend/src/main/resources/db/migration/V71__invoice_transaction_currency.sql`:

```sql
-- Currency for invoices and bank transactions.
--
-- The system had no concept of currency: MatchSuggester compared raw amounts within a tolerance and no
-- currency check, so a EUR 59.90 invoice could be suggested against a RON 59.90 payment — at ~5 RON/EUR,
-- two different payments. Clients buy abroad (Bolt rides, foreign hotels), so EUR invoices are routine.
--
-- NOT NULL DEFAULT 'RON' backfills every existing row in this one statement, which is intended: the
-- system has been RON-only in practice. It also keeps the column free of a null-means-RON rule that
-- every future reader would have to know.
ALTER TABLE invoice
    ADD COLUMN currency char(3) NOT NULL DEFAULT 'RON'
        CHECK (currency ~ '^[A-Z]{3}$');

ALTER TABLE bank_transaction
    ADD COLUMN currency char(3) NOT NULL DEFAULT 'RON'
        CHECK (currency ~ '^[A-Z]{3}$');
```

- [ ] **Step 4: Add the field to `Invoice`**

In `Invoice.java`, add next to `totalAmount`:

```java
    @Column(nullable = false)
    private String currency = "RON";
```

Append the parameter to the constructor (line 72) and default a null:

```java
    public Invoice(UUID tenantId, UUID documentId, UUID companyId, LocalDate periodMonth,
                   String supplierName, String supplierIban, BigDecimal totalAmount,
                   LocalDate invoiceDate, String originalFilename, String status, String currency) {
        ...
        this.status = status;
        // NOT NULL in the schema; a caller that cannot determine one means RON.
        this.currency = currency == null ? "RON" : currency;
    }
```

Append it to `updateExtraction` (line 88) the same way, and add accessors:

```java
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency == null ? "RON" : currency; }
```

- [ ] **Step 5: Add the field to `BankTransaction`**

In `BankTransaction.java`, add next to `amount`:

```java
    @Column(nullable = false)
    private String currency = "RON";
```

Append the parameter to the constructor (line 77), assigning before `this.requiresDocument = false;`:

```java
        this.balanceAfter = balanceAfter;
        this.currency = currency == null ? "RON" : currency;
        this.requiresDocument = false;
```

Add the getter next to `getAmount()`:

```java
    public String getCurrency() { return currency; }
```

- [ ] **Step 6: Fix the two call sites so the module compiles**

`InvoiceExtractionService.java:101` — append `"RON"` as the last argument to `new Invoice(...)`, and `:97` — append `"RON"` to `existing.updateExtraction(...)`. Task 3 replaces both with the real value.

`BankStatementExtractionService.java:167` — append `"RON"` as the last argument to `new BankTransaction(...)`. Task 4 replaces it.

- [ ] **Step 7: Run the test to verify it passes**

Run: `mvn -o -f backend/pom.xml test -Dtest=CurrencyDefaultTest`
Expected: PASS, 4 tests.

- [ ] **Step 8: Verify the migration applies and nothing else broke**

Run: `mvn -o -f backend/pom.xml test -Dtest='ro.myfinance.extraction.**.*Test'`
Expected: PASS. The Testcontainers integration tests run Flyway on a real Postgres, so a malformed migration fails here rather than in production.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/resources/db/migration/V71__invoice_transaction_currency.sql \
        backend/src/main/java/ro/myfinance/extraction/domain/Invoice.java \
        backend/src/main/java/ro/myfinance/extraction/domain/BankTransaction.java \
        backend/src/main/java/ro/myfinance/extraction/application/InvoiceExtractionService.java \
        backend/src/main/java/ro/myfinance/extraction/application/BankStatementExtractionService.java \
        backend/src/test/java/ro/myfinance/extraction/CurrencyDefaultTest.java
git commit -m "feat(extraction): add currency to invoice and bank_transaction, defaulting to RON"
```

---

### Task 2: Read the invoice's currency

**Files:**
- Modify: `backend/src/main/java/ro/myfinance/extraction/application/ParsedInvoice.java`
- Modify: `backend/src/main/java/ro/myfinance/extraction/adapter/external/HeuristicInvoiceExtractor.java`
- Test: `backend/src/test/java/ro/myfinance/extraction/HeuristicInvoiceExtractorTest.java` (existing, add cases)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `ParsedInvoice(String supplierName, String supplierIban, BigDecimal totalAmount, LocalDate invoiceDate, String clientCif, String invoiceNumber, String issuerCif, String currency)` — currency appended last, never null.

- [ ] **Step 1: Write the failing tests**

Append to `HeuristicInvoiceExtractorTest.java`:

```java
    @Test
    void readsTheCurrencyFromTheTotalLine() throws Exception {
        // Real text from "Ride invoice from Bolt 32.pdf" — the currency sits on the total's own line.
        ParsedInvoice inv = extractor.extract(pdf(
                "Rechnung",
                "Datum: 24-09-2026",
                "Fahrpreis 49.13 9.34 58.47",
                "Nettobetrag (EUR): 50.33 MwSt. 19%: 9.57 Bruttobetrag (EUR): 59.90"),
                "AGILE TRANSFORMATION TRAILBLAZERS SRL");
        assertThat(inv.currency()).isEqualTo("EUR");
    }

    @Test
    void readsTheCurrencyWhenOnlyNamedElsewhereInTheDocument() throws Exception {
        // Real shape from the GINN hotel bill: the grand total's line carries no code, but the document
        // names exactly one currency, so it is unambiguous.
        ParsedInvoice inv = extractor.extract(pdf(
                "Rechnungsnr.: GBP-260923075  Datum: 23.09.2026",
                "21.09.2026 Komfort Zimmer 1 137.92 EUR 7 % 9.65 EUR 147.57 EUR",
                "Zwischensumme (inkl. MwSt.) 1247.40 EUR"),
                "AGILE TRANSFORMATION TRAILBLAZERS SRL");
        assertThat(inv.currency()).isEqualTo("EUR");
    }

    @Test
    void treatsLeiAsRon() throws Exception {
        ParsedInvoice inv = extractor.extract(pdf(
                "Factura fiscala nr 5 din 12.09.2026",
                "Total de plata 1.190,00 LEI"),
                "ACME SRL");
        assertThat(inv.currency()).isEqualTo("RON");
    }

    @Test
    void defaultsToRonWhenNoCurrencyIsNamed() throws Exception {
        // Romanian invoices frequently name no currency at all.
        ParsedInvoice inv = extractor.extract(pdf(
                "Factura fiscala nr 6 din 12.09.2026",
                "Total de plata 1.190,00"),
                "ACME SRL");
        assertThat(inv.currency()).isEqualTo("RON");
    }

    @Test
    void theTotalsLineWinsWhenTwoCurrenciesAppear() throws Exception {
        // An invoice that quotes an EUR reference price but is payable in RON.
        ParsedInvoice inv = extractor.extract(pdf(
                "Factura fiscala nr 7 din 12.09.2026",
                "Valoare informativa 240,00 EUR",
                "Total de plata 1.190,00 RON"),
                "ACME SRL");
        assertThat(inv.currency()).isEqualTo("RON");
    }

    @Test
    void readsACurrencySymbol() throws Exception {
        ParsedInvoice inv = extractor.extract(pdf(
                "Invoice 9 dated 12.09.2026",
                "Total due 1,190.00 EUR"),
                "ACME SRL");
        assertThat(inv.currency()).isEqualTo("EUR");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -o -f backend/pom.xml test -Dtest=HeuristicInvoiceExtractorTest`
Expected: FAIL to compile — `ParsedInvoice` has no `currency()`.

- [ ] **Step 3: Add the field to `ParsedInvoice`**

```java
public record ParsedInvoice(String supplierName, String supplierIban, BigDecimal totalAmount,
                            LocalDate invoiceDate, String clientCif, String invoiceNumber,
                            String issuerCif, String currency) {
}
```

- [ ] **Step 4: Make `totalAmount` report the line it chose**

In `HeuristicInvoiceExtractor`, the currency must come from the same line the total came from, so the
total's selection has to report that line. Add the record and change the three `return`s of
`totalAmount(String)` to carry it, renaming the method to `total`:

```java
    /** The chosen total and the line it was read from — the currency is taken from that same line. */
    private record Total(BigDecimal amount, String line) {
    }

    private Total total(String text) {
        String[] lines = text.split("\\R");
        // 1) The authoritative pay total (unchanged selection logic).
        for (int i = 0; i < lines.length; i++) {
            String n = norm(lines[i]);
            boolean payTotal = (n.contains("total") && n.contains("plata")) || n.contains("total general");
            if (!payTotal) {
                continue;
            }
            BigDecimal best = null;
            String bestLine = null;
            for (int j = i; j < Math.min(lines.length, i + 4); j++) {
                for (Matcher m = MONEY.matcher(lines[j]); m.find(); ) {
                    BigDecimal v = parseMoney(m.group());
                    if (best == null || v.compareTo(best) > 0) {
                        best = v;
                        bestLine = lines[j];
                    }
                }
            }
            if (best != null) {
                return new Total(best, bestLine);
            }
        }
        // 2) Else the largest money on any "total" line; 3) else the largest money overall.
        BigDecimal labelled = null, largest = null;
        String labelledLine = null, largestLine = null;
        for (String line : lines) {
            boolean totalLine = norm(line).contains("total");
            for (Matcher m = MONEY.matcher(line); m.find(); ) {
                BigDecimal v = parseMoney(m.group());
                if (largest == null || v.compareTo(largest) > 0) {
                    largest = v;
                    largestLine = line;
                }
                if (totalLine && (labelled == null || v.compareTo(labelled) > 0)) {
                    labelled = v;
                    labelledLine = line;
                }
            }
        }
        return labelled != null ? new Total(labelled, labelledLine) : new Total(largest, largestLine);
    }
```

- [ ] **Step 5: Add currency detection**

Add to `HeuristicInvoiceExtractor`:

```java
    /** Recognised currency tokens. "LEI" is how Romanian invoices often write RON. */
    private static final Pattern CURRENCY_TOKEN = Pattern.compile(
            "(?i)(?<![a-z])(RON|LEI|EUR|USD|GBP|CHF|HUF)(?![a-z])|([€£])");

    private static String normaliseCurrency(String token) {
        String t = token.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (t) {
            case "LEI" -> "RON";
            case "€" -> "EUR";
            case "£" -> "GBP";
            default -> t;
        };
    }

    private static String firstCurrencyIn(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = CURRENCY_TOKEN.matcher(line);
        return m.find() ? normaliseCurrency(m.group()) : null;
    }

    /**
     * The invoice's currency: the code on the total's own line first, else the only code named anywhere
     * in the document, else RON.
     *
     * <p>RON is the right default rather than "unknown": a Romanian invoice frequently names no currency,
     * and the column is NOT NULL. Being wrong here degrades to today's behaviour, and the accountant can
     * see the value and correct it.
     */
    private String currency(String text, String totalLine) {
        String onTotalLine = firstCurrencyIn(totalLine);
        if (onTotalLine != null) {
            return onTotalLine;
        }
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        for (Matcher m = CURRENCY_TOKEN.matcher(text); m.find(); ) {
            all.add(normaliseCurrency(m.group()));
        }
        return all.size() == 1 ? all.iterator().next() : "RON";
    }
```

- [ ] **Step 6: Wire it into `extract`**

In `extract(byte[], String)`, replace the `total = totalAmount(text);` line and the `return` so the
currency travels with the result:

```java
            String currency = "RON";
            try {
                iban = firstMatch(IBAN, text);
                Total tot = total(text);
                total = tot.amount();
                currency = currency(text, tot.line());
                date = invoiceDate(text);
                ...
            } catch (RuntimeException e) {
                log.debug("Invoice field extraction failed", e);
            }
            return new ParsedInvoice(supplier, iban, total, date, clientCif, invoiceNumber, issuerCif, currency);
```

Both `return new ParsedInvoice(...)` sites in this file take the extra argument; the failure path
at the end of `extract` returns `"RON"`:

```java
            return new ParsedInvoice(null, null, null, null, null, null, null, "RON");
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn -o -f backend/pom.xml test -Dtest=HeuristicInvoiceExtractorTest`
Expected: PASS, 13 tests — the 7 that existed plus the 6 added. The pre-existing cases must still pass:
the total-selection logic is unchanged, only its return type.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/ro/myfinance/extraction/application/ParsedInvoice.java \
        backend/src/main/java/ro/myfinance/extraction/adapter/external/HeuristicInvoiceExtractor.java \
        backend/src/test/java/ro/myfinance/extraction/HeuristicInvoiceExtractorTest.java
git commit -m "feat(extraction): read the invoice's currency from the document"
```

---

### Task 3: Store the invoice's currency

**Files:**
- Modify: `backend/src/main/java/ro/myfinance/extraction/application/InvoiceExtractionService.java` (`Fields` record at line 46, `fromPdf` at 130, `fromReceiptImages` at 143, `identifyClientParty` at 163, persistence at 95-105)
- Test: `backend/src/test/java/ro/myfinance/extraction/InvoiceCurrencyPersistedTest.java`

**Interfaces:**
- Consumes: `ParsedInvoice.currency()` (Task 2); `Invoice(..., String currency)` and `Invoice.updateExtraction(..., String currency)` (Task 1).
- Produces: `InvoiceExtractionService.Fields(String supplierName, String supplierIban, BigDecimal total, LocalDate date, String status, String issuerCif, String clientCif, String receiptNumber, Boolean wrongParty, String currency)` — currency appended last.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/ro/myfinance/extraction/InvoiceCurrencyPersistedTest.java`:

```java
package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.application.InvoiceExtractionService;

/**
 * The currency the extractor read has to reach the stored row. Exercised through Fields, which is the
 * one shape every intake path (PDF, receipt image) funnels into before persistence.
 */
class InvoiceCurrencyPersistedTest {

    private InvoiceExtractionService.Fields fields(String currency) {
        return new InvoiceExtractionService.Fields("Bolt Operations OU", null,
                new BigDecimal("59.90"), LocalDate.of(2026, 9, 24), "EXTRACTED",
                null, "50109730", "32", Boolean.FALSE, currency);
    }

    @Test
    void fieldsCarryTheCurrency() {
        assertThat(fields("EUR").currency()).isEqualTo("EUR");
    }

    @Test
    void identifyClientPartyPreservesTheCurrency() {
        // This helper rebuilds Fields; a rebuild that forgot the currency would silently reset it to RON.
        InvoiceExtractionService.Fields out =
                InvoiceExtractionService.identifyClientParty(fields("EUR"), "50109730", "AGILE SRL");
        assertThat(out.currency()).isEqualTo("EUR");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -f backend/pom.xml test -Dtest=InvoiceCurrencyPersistedTest`
Expected: FAIL to compile — `Fields` has nine components, not ten.

- [ ] **Step 3: Extend `Fields`**

```java
    record Fields(String supplierName, String supplierIban, BigDecimal total, LocalDate date,
                  String status, String issuerCif, String clientCif, String receiptNumber,
                  Boolean wrongParty, String currency) {
    }
```

- [ ] **Step 4: Populate it on every path**

`fromPdf` (line 130) passes the extractor's value:

```java
        return new Fields(p.supplierName(), p.supplierIban(), p.totalAmount(), p.invoiceDate(), status,
                p.issuerCif(), p.clientCif(), p.invoiceNumber(), wrongParty, p.currency());
```

`fromReceiptImages` (line 143) passes `"RON"`. `ParsedReceipt` does carry a currency, but receipt
currency is out of scope for this spec — wiring it up is a separate change, and silently using it here
would make the behaviour inconsistent with the spec's stated scope:

```java
        // Receipt currency is out of scope (spec, "Out of scope") — ParsedReceipt carries one, but
        // nothing reads it yet. RON keeps receipts at today's behaviour.
```

Every other place that constructs `Fields` — including `identifyClientParty` (line 163), which rebuilds
it — must thread the existing `currency` through rather than re-defaulting it.

- [ ] **Step 5: Pass it to persistence**

Replace the two placeholder `"RON"` arguments added in Task 1 Step 6 with the real value (lines 95-105):

```java
                existing -> {
                    existing.setPeriodMonth(targetPeriod);
                    existing.updateExtraction(f.supplierName(), f.supplierIban(), f.total(), f.date(),
                            filename, f.status(), f.currency());
                    existing.updateReceiptFields(f.issuerCif(), f.clientCif(), f.receiptNumber(), f.wrongParty());
                },
                () -> {
                    Invoice inv = new Invoice(tenantId, documentId, companyId, targetPeriod,
                            f.supplierName(), f.supplierIban(), f.total(), f.date(), filename,
                            f.status(), f.currency());
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -o -f backend/pom.xml test -Dtest='InvoiceCurrencyPersistedTest,InvoiceExtractionPartyTest,CurrencyDefaultTest'`
Expected: PASS. `InvoiceExtractionPartyTest` (6 existing tests) exercises `identifyClientParty` and must stay green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/ro/myfinance/extraction/application/InvoiceExtractionService.java \
        backend/src/test/java/ro/myfinance/extraction/InvoiceCurrencyPersistedTest.java
git commit -m "feat(extraction): persist the invoice currency"
```

---

### Task 4: Stamp transactions with the statement's currency

**Files:**
- Modify: `backend/src/main/java/ro/myfinance/extraction/application/ParsedStatement.java`
- Modify: `backend/src/main/java/ro/myfinance/extraction/adapter/external/Mt940StatementParser.java`
- Modify: `backend/src/main/java/ro/myfinance/extraction/adapter/external/Camt053StatementParser.java`
- Modify: `backend/src/main/java/ro/myfinance/extraction/adapter/external/RevolutStatementParser.java`
- Modify: `BrdStatementParser.java`, `BancaTransilvaniaStatementParser.java`, `IngStatementParser.java`, `LibraStatementParser.java`, `GenericRunningBalanceParser.java` (all in the same package — pass `"RON"`)
- Modify: `backend/src/main/java/ro/myfinance/extraction/application/BankStatementExtractionService.java:167`
- Test: `backend/src/test/java/ro/myfinance/extraction/StatementCurrencyTest.java`

**Interfaces:**
- Consumes: `BankTransaction(..., String currency)` (Task 1).
- Produces: `ParsedStatement(String bankCode, String accountIban, BigDecimal openingBalance, BigDecimal closingBalance, List<ParsedTransaction> transactions, String currency)` — currency appended last, never null.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/ro/myfinance/extraction/StatementCurrencyTest.java`:

```java
package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.adapter.external.Camt053StatementParser;
import ro.myfinance.extraction.adapter.external.Mt940StatementParser;
import ro.myfinance.extraction.application.ParsedStatement;

/**
 * In Spec A every stored transaction is RON, because only RON statement sections are ingested. These
 * assert the currency is nonetheless carried through from the parsers that genuinely know it, so Spec B
 * has a working channel rather than needing a second migration.
 */
class StatementCurrencyTest {

    @Test
    void mt940CarriesTheCurrencyFromItsBalanceTag() {
        // :60F: content is C/D + YYMMDD + CCY + amount; the parser already captures CCY as group 3.
        String mt940 = ":20:REF\n:25:RO98INGB0000999905473924\n:60F:C260101RON1000,00\n"
                + ":61:2601010101D50,00NTRFNONREF\n:86:Plata\n:62F:C260101RON950,00\n";
        ParsedStatement s = new Mt940StatementParser().parse(mt940);
        assertThat(s.currency()).isEqualTo("RON");
    }

    @Test
    void camtReadsTheCcyAttributeNotJustTheAmountText() {
        // The currency in CAMT.053 is an ATTRIBUTE on <Amt>, which the parser did not read at all.
        String camt = "<?xml version=\"1.0\"?><Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.02\">"
                + "<BkToCstmrStmt><Stmt><Id>1</Id>"
                + "<Acct><Id><IBAN>RO98INGB0000999905473924</IBAN></Id></Acct>"
                + "<Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy=\"EUR\">100.00</Amt>"
                + "<CdtDbtInd>CRDT</CdtDbtInd></Bal>"
                + "<Ntry><Amt Ccy=\"EUR\">25.00</Amt><CdtDbtInd>DBIT</CdtDbtInd>"
                + "<BookgDt><Dt>2026-01-05</Dt></BookgDt></Ntry>"
                + "</Stmt></BkToCstmrStmt></Document>";
        ParsedStatement s = new Camt053StatementParser().parse(camt);
        assertThat(s.currency()).isEqualTo("EUR");
    }

    @Test
    void aParserThatCannotKnowTheCurrencyReportsRon() {
        // Text-PDF bank parsers name no currency reliably, and RON is correct for every statement they
        // handle. Asserted on the record itself so the contract "never null" is pinned.
        ParsedStatement s = new ParsedStatement("BRD", "RO49BRDE", null, null, java.util.List.of(), null);
        assertThat(s.currency()).isEqualTo("RON");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -f backend/pom.xml test -Dtest=StatementCurrencyTest`
Expected: FAIL to compile — `ParsedStatement` has five components and no `currency()`.

- [ ] **Step 3: Add the field to `ParsedStatement`, defaulting null to RON**

```java
public record ParsedStatement(String bankCode, String accountIban, BigDecimal openingBalance,
                              BigDecimal closingBalance, List<ParsedTransaction> transactions,
                              String currency) {
    public ParsedStatement {
        // Never null: the column is NOT NULL and most parsers legitimately cannot tell.
        currency = currency == null ? "RON" : currency.toUpperCase(java.util.Locale.ROOT);
    }
}
```

- [ ] **Step 4: Pass the currency from the three parsers that have it**

`Mt940StatementParser` — `BALANCE` (line 38) is `^([CD])(\d{6})([A-Z]{3})([\d.,]+)`, so group 3 is the
code. The existing `balance(String)` helper (line 157) returns only the amount and discards it, so add a
sibling that returns the code, and capture it in the `60F`/`60M` branch of the tag switch (line 65):

```java
    /** The 3-letter currency from a :60F:/:62F: balance tag — group 3 of BALANCE, which balance() drops. */
    private String balanceCurrency(String value) {
        Matcher m = BALANCE.matcher(value.trim());
        return m.find() ? m.group(3) : null;
    }
```

Declare `String currency = null;` beside `BigDecimal opening = null;` (line 56), then in the switch:

```java
                case "60F", "60M" -> {
                    BigDecimal b = balance(value);
                    if (opening == null) {
                        opening = b; // opening = first opening balance (60M = intermediate on multi-page)
                        currency = balanceCurrency(value); // same tag carries the statement's currency
                    }
                }
```

and pass it at line 90:

```java
        return new ParsedStatement("MT940", accountIban, opening, closing, txns, currency);
```

`Camt053StatementParser` — the parser reads only element text via `firstText(ntry, "Amt")`. Add an
attribute read and use it:

```java
    /** CAMT.053 puts the currency in the Ccy ATTRIBUTE of <Amt>, not in its text. */
    private String currencyOf(Element parent) {
        Element amt = firstChild(parent, "Amt");
        if (amt == null) {
            return null;
        }
        String ccy = amt.getAttribute("Ccy");
        return ccy == null || ccy.isBlank() ? null : ccy;
    }
```

Call it on the first `Bal` element, falling back to the first `Ntry`, and pass the result.

`RevolutStatementParser` — `isRonSection` already matches `Currency\s+RON`. Keep the RON-only filter
(Spec B changes that) and pass `"RON"` explicitly, with a comment that the section currency is known here
and is where Spec B will branch.

- [ ] **Step 5: Pass `"RON"` from the remaining parsers**

`BrdStatementParser`, `BancaTransilvaniaStatementParser`, `IngStatementParser`, `LibraStatementParser`,
`GenericRunningBalanceParser`: append `"RON"` to each `new ParsedStatement(...)`.

- [ ] **Step 6: Stamp each transaction**

`BankStatementExtractionService.java:167` — replace the placeholder `"RON"` from Task 1 with the
statement's currency:

```java
            transactions.save(new BankTransaction(tenantId, companyId, statement.getId(),
                    parsed.accountIban(), t.date(), t.amount(), dir, t.partnerName(), t.partnerIban(),
                    t.description(), t.ref(), t.balanceAfter(), parsed.currency()));
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn -o -f backend/pom.xml test -Dtest='StatementCurrencyTest,Mt940StatementParserTest,Camt053StatementParserTest,RevolutStatementParserTest,BrdStatementParserTest,LibraStatementParserTest,IngStatementParserTest,BancaTransilvaniaStatementParserTest,GenericRunningBalanceParserTest,IngStatementRegistryPipelineTest'`
Expected: PASS. Every existing parser test must stay green — only the record's arity changed.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/ro/myfinance/extraction/application/ParsedStatement.java \
        backend/src/main/java/ro/myfinance/extraction/application/BankStatementExtractionService.java \
        backend/src/main/java/ro/myfinance/extraction/adapter/external/ \
        backend/src/test/java/ro/myfinance/extraction/StatementCurrencyTest.java
git commit -m "feat(extraction): carry the statement currency onto each transaction"
```

---

### Task 5: Never suggest a match across currencies

**Files:**
- Modify: `backend/src/main/java/ro/myfinance/extraction/application/ReconciliationService.java` (`OpenInvoiceView` at line 42, `OpenTxnView` at line 76, and the queries that build them)
- Modify: `backend/src/main/java/ro/myfinance/extraction/application/MatchSuggester.java` (exact path around line 74, subset-sum path around line 156)
- Test: `backend/src/test/java/ro/myfinance/extraction/MatchSuggesterCurrencyTest.java`

**Interfaces:**
- Consumes: `Invoice.getCurrency()`, `BankTransaction.getCurrency()` (Task 1).
- Produces: `OpenInvoiceView(..., String currency)` and `OpenTxnView(..., String currency)` — currency appended last on both.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/ro/myfinance/extraction/MatchSuggesterCurrencyTest.java`.

There is no existing `MatchSuggester` test, so this is the first. The suggester is **not** pure over
views: its API is `MatchSuggester(ReconciliationView view)` and `suggestions(UUID companyId,
LocalDate periodMonth)`, and it reads `view.openTransactions(companyId, periodMonth, 0)` and
`view.openInvoices(companyId, periodMonth, 18)`. So mock `ReconciliationView` and stub those two calls.

Note the two conditions the production code already requires, or every test here returns empty for the
wrong reason: an invoice is only considered when `supplierIban() != null`, `remaining() != null` and
`wrongParty()` is not `TRUE`; a transaction only when `partnerIban() != null`. Pairing happens **per
IBAN**, so both sides must carry the same IBAN.

```java
package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.application.MatchSuggester;
import ro.myfinance.extraction.application.ReconciliationService.OpenInvoiceView;
import ro.myfinance.extraction.application.ReconciliationService.OpenTxnView;
import ro.myfinance.extraction.application.ReconciliationView;

/**
 * The defect this closes: amounts were compared within a tolerance and no currency check, so a EUR 59.90
 * invoice could be suggested against a RON 59.90 payment — at ~5 RON/EUR, two different payments.
 */
class MatchSuggesterCurrencyTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final LocalDate PERIOD = LocalDate.of(2026, 9, 1);
    // Pairing is per-IBAN, so both sides must share one.
    private static final String IBAN = "RO49AAAA1B31007593840000";

    private OpenInvoiceView invoice(String amount, String currency) {
        BigDecimal v = new BigDecimal(amount);
        return new OpenInvoiceView(UUID.randomUUID(), UUID.randomUUID(), "f.pdf", "Bolt", IBAN, v,
                LocalDate.of(2026, 9, 24), PERIOD, BigDecimal.ZERO, v,
                false, Boolean.FALSE, null, "50109730", currency);
    }

    private OpenTxnView txn(String amount, String currency) {
        BigDecimal v = new BigDecimal(amount);
        return new OpenTxnView(UUID.randomUUID(), LocalDate.of(2026, 9, 25), v, "Bolt", IBAN,
                BigDecimal.ZERO, v, currency);
    }

    private List<?> suggest(List<OpenTxnView> txns, List<OpenInvoiceView> invs) {
        ReconciliationView view = mock(ReconciliationView.class);
        when(view.openTransactions(eq(COMPANY), eq(PERIOD), anyInt())).thenReturn(txns);
        when(view.openInvoices(eq(COMPANY), eq(PERIOD), anyInt())).thenReturn(invs);
        return new MatchSuggester(view).suggestions(COMPANY, PERIOD);
    }

    @Test
    void doesNotSuggestAEuroInvoiceForARonPaymentOfTheSameNumber() {
        assertThat(suggest(List.of(txn("59.90", "RON")), List.of(invoice("59.90", "EUR")))).isEmpty();
    }

    @Test
    void stillSuggestsWhenBothSidesAreTheSameCurrency() {
        assertThat(suggest(List.of(txn("59.90", "RON")), List.of(invoice("59.90", "RON")))).hasSize(1);
    }

    @Test
    void stillSuggestsWhenBothSidesAreEuro() {
        assertThat(suggest(List.of(txn("59.90", "EUR")), List.of(invoice("59.90", "EUR")))).hasSize(1);
    }

    @Test
    void doesNotCombineInvoicesOfDifferentCurrenciesToReachOnePayment() {
        // The subset-sum path: 40 EUR + 19.90 RON must not be offered as settling a 59.90 RON payment.
        assertThat(suggest(List.of(txn("59.90", "RON")),
                List.of(invoice("40.00", "EUR"), invoice("19.90", "RON")))).isEmpty();
    }

    @Test
    void stillCombinesTwoInvoicesOfTheSameCurrency() {
        // Guards the opposite error: filtering by currency must not disable the subset-sum path.
        assertThat(suggest(List.of(txn("59.90", "RON")),
                List.of(invoice("40.00", "RON"), invoice("19.90", "RON")))).hasSize(1);
    }
}
```

If `openTransactions` / `openInvoices` turn out to have different parameter counts, match the call shapes
used in `MatchSuggester.suggestions` lines 37-38 exactly — those two lines are the contract this mock
stands in for.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -f backend/pom.xml test -Dtest=MatchSuggesterCurrencyTest`
Expected: FAIL to compile — the view records take one fewer argument.

- [ ] **Step 3: Add currency to both views**

```java
    public record OpenInvoiceView(UUID invoiceId, UUID documentId, String filename, String supplierName,
                                  String supplierIban, java.math.BigDecimal totalAmount,
                                  java.time.LocalDate invoiceDate, java.time.LocalDate periodMonth,
                                  java.math.BigDecimal paidAmount, java.math.BigDecimal remaining,
                                  boolean duplicate, Boolean wrongParty, String issuerCif, String clientCif,
                                  String currency) {
    }

    public record OpenTxnView(UUID id, java.time.LocalDate txnDate, java.math.BigDecimal amount,
                              String partnerName, String partnerIban, java.math.BigDecimal allocatedAmount,
                              java.math.BigDecimal remaining, String currency) {
    }
```

Populate both from the entities wherever `ReconciliationService` builds them — `inv.getCurrency()` and
`txn.getCurrency()`.

- [ ] **Step 4: Guard both matching paths**

In `MatchSuggester`, add the helper and use it in both places:

```java
    /**
     * Two amounts are only comparable in the same currency. There is no FX rate anywhere in the system,
     * and at ~5 RON/EUR a numeric coincidence is a five-fold error — so a cross-currency pair is not a
     * candidate at any confidence. Manual linking stays allowed: the accountant knows the day's rate.
     */
    private static boolean sameCurrency(OpenTxnView t, OpenInvoiceView i) {
        return t.currency().equalsIgnoreCase(i.currency());
    }
```

Exact path (line 74) — add to the `continue` condition so a mismatched pair is skipped before the
amount comparison:

```java
                    if (usedInvs.contains(i.invoiceId()) || !dateOk(t, i) || !sameCurrency(t, i)) {
                        continue;
                    }
```

Subset-sum path — filter the candidate invoices to the transaction's currency *before* the subset search,
so no combination can mix currencies:

```java
        List<OpenInvoiceView> sameCcy = invs.stream().filter(i -> sameCurrency(t, i)).toList();
```

and run the subset search over `sameCcy` instead of `invs`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -o -f backend/pom.xml test -Dtest=MatchSuggesterCurrencyTest`
Expected: PASS, 4 tests.

- [ ] **Step 6: Verify manual linking still crosses currencies**

The guard must live in `MatchSuggester` only. Confirm `ReconciliationService.match(...)` has no currency
check:

Run: `grep -n 'sameCurrency\|currency' backend/src/main/java/ro/myfinance/extraction/application/ReconciliationService.java`
Expected: hits only in the two view records and where they are populated — **none** inside `match(...)`.

- [ ] **Step 7: Run the whole extraction and intake suites**

Run: `mvn -o -f backend/pom.xml test -Dtest='ro.myfinance.extraction.**.*Test,ro.myfinance.intake.*Test'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/ro/myfinance/extraction/application/ReconciliationService.java \
        backend/src/main/java/ro/myfinance/extraction/application/MatchSuggester.java \
        backend/src/test/java/ro/myfinance/extraction/MatchSuggesterCurrencyTest.java
git commit -m "fix(reconciliation): never suggest a match across currencies"
```

---

### Task 6: Expose and display the currency

**Files:**
- Modify: `backend/src/main/java/ro/myfinance/extraction/adapter/web/BankStatementDtos.java` (invoice / open-invoice / transaction responses)
- Modify: `frontend/src/api/bank.ts` (`OpenInvoice`, `BankTransaction`, and the invoice shapes at lines 43, 56, 127, 161)
- Create: `frontend/src/lib/money.ts`
- Create: `frontend/src/lib/money.test.ts`
- Modify: `frontend/src/pages/ReconcileWorkspace.tsx`, `frontend/src/components/FilesModal.tsx`, `frontend/src/components/LinkTransactionModal.tsx`, `frontend/src/components/InvoicePaymentsModal.tsx`

**Interfaces:**
- Consumes: `OpenInvoiceView.currency()`, `OpenTxnView.currency()` (Task 5); `Invoice.getCurrency()` (Task 1).
- Produces: `formatMoney(amount: number | string | null | undefined, currency: string, lang: string): string`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/lib/money.test.ts`:

```ts
import { describe, expect, it } from "vitest";

import { formatMoney } from "./money";

describe("formatMoney", () => {
  it("omits the suffix for RON so a RON-only client sees no new noise", () => {
    // The regression this guards: every amount on every screen suddenly growing a redundant "RON".
    expect(formatMoney(1190, "RON", "ro")).not.toContain("RON");
    expect(formatMoney(1190, "RON", "en")).not.toContain("RON");
  });

  it("shows the currency when it is not RON, so EUR is never read as RON", () => {
    expect(formatMoney(59.9, "EUR", "en")).toContain("EUR");
    expect(formatMoney(59.9, "EUR", "ro")).toContain("EUR");
  });

  it("always shows two decimals", () => {
    expect(formatMoney(1190, "RON", "en")).toContain(".00");
    expect(formatMoney(59.9, "EUR", "en")).toContain(".90");
  });

  it("renders a missing amount as a dash, never NaN", () => {
    expect(formatMoney(null, "RON", "en")).toBe("—");
    expect(formatMoney(undefined, "EUR", "en")).toBe("—");
    expect(formatMoney("not-a-number", "RON", "en")).toBe("—");
  });

  it("accepts a numeric string, as the API returns", () => {
    expect(formatMoney("59.90", "EUR", "en")).toContain("59.90");
  });

  it("treats a missing currency as RON rather than printing undefined", () => {
    expect(formatMoney(10, "", "en")).not.toContain("undefined");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --run money`
Expected: FAIL — cannot resolve `./money`.

- [ ] **Step 3: Write `money.ts`**

```ts
/**
 * One money formatter for the whole app. Amounts used to be formatted inline in at least eight
 * components, which is how an amount could be shown with no indication of its currency at all.
 *
 * The currency is printed ONLY when it is not RON: almost every client is RON-only, and suffixing every
 * amount with "RON" is noise — while a EUR amount shown bare is genuinely dangerous, because it reads as
 * RON and the two differ by roughly a factor of five.
 */
const DEFAULT_CURRENCY = "RON";

export function formatMoney(amount: number | string | null | undefined,
                            currency: string, lang: string): string {
  const n = typeof amount === "string" ? Number(amount) : amount;
  if (n === null || n === undefined || !Number.isFinite(n)) {
    return "—";
  }
  const locale = lang === "ro" ? "ro-RO" : "en-US";
  const text = n.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const ccy = (currency || DEFAULT_CURRENCY).toUpperCase();
  return ccy === DEFAULT_CURRENCY ? text : `${text} ${ccy}`;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- --run money`
Expected: PASS, 6 tests.

- [ ] **Step 5: Expose the currency over the API**

In `BankStatementDtos.java`, add `String currency` as the last component of these three records, each
populated from the corresponding getter:

| Record | Line | Source of the value |
|---|---|---|
| `InvoiceResponse` | 29 | `Invoice.getCurrency()` |
| `OpenInvoiceResponse` | 41 | `OpenInvoiceView.currency()` |
| `TransactionResponse` | 52 | `BankTransaction.getCurrency()` |

Leave `MatchedInvoiceResponse`, `PaymentResponse` and `InvoicePaymentsResponse` alone — they are not used
by the surfaces this task touches, and widening them is scope creep.

In `frontend/src/api/bank.ts`, add `currency: string;` beside `totalAmount` in the interfaces at lines
43, 56, 127 and 161, and to `BankTransaction` (line 61).

- [ ] **Step 6: Use the formatter where a mismatch costs money**

Replace inline amount formatting with `formatMoney(value, row.currency, i18n.language)` in:
`ReconcileWorkspace.tsx` (transaction and open-invoice lists), `FilesModal.tsx` (invoice amounts),
`LinkTransactionModal.tsx` (both sides of the link — the currency must be visible on each), and
`InvoicePaymentsModal.tsx`. Leave other inline formatting alone; sweeping it all is out of scope.

- [ ] **Step 7: Typecheck, lint, test, build**

Run:
```bash
cd frontend && npx tsc --noEmit && npm run lint && npm test -- --run && npm run build
```
Expected: typecheck clean, 0 eslint errors, all tests pass, build succeeds.

- [ ] **Step 8: Run the backend suites once more**

Run: `mvn -o -f backend/pom.xml test -Dtest='ro.myfinance.extraction.**.*Test,ro.myfinance.intake.*Test,ro.myfinance.ingestion.*Test'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/ro/myfinance/extraction/adapter/web/BankStatementDtos.java \
        frontend/src/api/bank.ts frontend/src/lib/money.ts frontend/src/lib/money.test.ts \
        frontend/src/pages/ReconcileWorkspace.tsx frontend/src/components/FilesModal.tsx \
        frontend/src/components/LinkTransactionModal.tsx frontend/src/components/InvoicePaymentsModal.tsx
git commit -m "feat(ui): show the currency beside amounts that are not RON"
```

---

## Release note

Making currency explicit **reduces** suggestions: anything previously offered only because the raw
numbers coincided across currencies will stop being suggested. That is the fix working, but an accountant
may read it as a regression, so it belongs in the release note rather than being discovered.

A EUR invoice paid from a EUR Revolut sub-account still reads as **unpaid** — that payment is not
ingested at all. Spec B covers it.
