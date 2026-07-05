# Document Intake (Statements & Invoices — Slice A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: subagent-driven-development (recommended) or executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let firm staff upload documents for a company+period; the system classifies the type, stores the file behind a storage port, and lists/downloads/deletes them.

**Architecture:** New `ro.myfinance.intake` module. `DocumentStorage` port (local-FS dev adapter + Supabase Storage prod adapter, factory-selected). `DocumentClassifier` port with a deterministic PDFBox-based heuristic impl. RLS-scoped `document` table (Flyway V4). Frontend `/statements` page.

**Tech Stack:** Java 21, Spring Boot 3.5.9, Spring Data JPA, Flyway, Apache PDFBox 3, Testcontainers; React 18 + TS + Vite, TanStack Query, react-i18next.

---

## Context for the implementer

- **Multi-tenancy:** every table has `tenant_id`; `RlsDataSource` sets `app.tenant_id` per connection from `TenantContext`. In services/ITs, `TenantContext.set(...)` before any DB op; `TenantContext.clear()` in `@AfterEach`. See existing `SettingsServiceIT` / `CompanyServiceIT`.
- **Module layout:** `ro.myfinance.<module>/{domain,application,adapter/{persistence,web,external}}`.
- **Errors:** `ConflictException`(409), `NotFoundException`(404) in `ro.myfinance.common.web`; `IllegalArgumentException`→400 is already mapped by `ApiExceptionHandler`. Use `IllegalArgumentException` for bad file type/size.
- **Audit:** `ro.myfinance.common.audit.AuditRecorder.record(String action, String entity, UUID entityId)`.
- **Config pattern:** factory selection mirrors `ro.myfinance.access.adapter.external.RepresentativeInviterConfig` + `ro.myfinance.common.config.SupabaseProperties` (record with `url`, `serviceRoleKey`, `isConfigured()`).
- **Docker is NOT installed locally.** `*IT` extend `AbstractPostgresIT` (`disabledWithoutDocker=true`) — they skip locally, run in CI. Verify locally with `mvn -B -DskipTests test-compile` and `mvn -B test` (unit tests pass; ITs skip).
- **Flyway:** local DB is past `V1000` seed; `out-of-order: true` is set for the local profile so `V4` applies. Test harness uses only `db/migration`.

## File map

**Create (backend):**
- `backend/src/main/resources/db/migration/V4__document.sql`
- `backend/src/main/java/ro/myfinance/intake/domain/{DocumentType,DocumentStatus,DocumentSource,Document}.java`
- `backend/src/main/java/ro/myfinance/intake/adapter/persistence/DocumentRepository.java`
- `backend/src/main/java/ro/myfinance/intake/application/{DocumentStorage,StoredObject,DocumentClassifier,DocumentService}.java`
- `backend/src/main/java/ro/myfinance/intake/adapter/external/{StorageProperties,LocalFsDocumentStorage,SupabaseDocumentStorage,DocumentStorageConfig,HeuristicDocumentClassifier}.java`
- `backend/src/main/java/ro/myfinance/intake/adapter/web/{DocumentDtos,DocumentController}.java`
- `backend/src/test/java/ro/myfinance/intake/{HeuristicDocumentClassifierTest,LocalFsDocumentStorageTest,DocumentServiceIT}.java`

**Modify (backend):** `backend/pom.xml` (PDFBox).

**Create (frontend):** `frontend/src/api/documents.ts`, `frontend/src/pages/Statements.tsx`
**Modify (frontend):** `frontend/src/lib/apiClient.ts`, `frontend/src/i18n.ts`, `frontend/src/App.tsx`

---

## Task 1: V4 migration + PDFBox dependency

**Files:** Create `backend/src/main/resources/db/migration/V4__document.sql`; Modify `backend/pom.xml`.

- [ ] **Step 1:** Create `V4__document.sql`:
```sql
CREATE TABLE document (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenant(id),
    company_id        uuid NOT NULL REFERENCES company(id),
    period_month      date NOT NULL,
    type              text NOT NULL,
    source            text NOT NULL,
    status            text NOT NULL,
    original_filename text NOT NULL,
    content_type      text NOT NULL,
    size_bytes        bigint NOT NULL,
    storage_key       text NOT NULL,
    uploaded_by       uuid,
    uploaded_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_document_company_period ON document(tenant_id, company_id, period_month);

ALTER TABLE document ENABLE ROW LEVEL SECURITY;
ALTER TABLE document FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON document TO myfinance_app;
    END IF;
END $$;
```

- [ ] **Step 2:** Add PDFBox to `backend/pom.xml` inside `<dependencies>` (before `</dependencies>` at line ~110):
```xml
        <dependency>
            <groupId>org.apache.pdfbox</groupId>
            <artifactId>pdfbox</artifactId>
            <version>3.0.3</version>
        </dependency>
```

- [ ] **Step 3:** Verify: `cd backend && mvn -B -DskipTests test-compile` → BUILD SUCCESS (downloads PDFBox).

- [ ] **Step 4:** Commit:
```bash
git add backend/src/main/resources/db/migration/V4__document.sql backend/pom.xml
git commit -m "feat(intake): V4 document table + PDFBox dependency"
```

---

## Task 2: Intake domain + repository

**Files:** Create the 4 domain files + `DocumentRepository`.

- [ ] **Step 1:** `intake/domain/DocumentType.java`:
```java
package ro.myfinance.intake.domain;

public enum DocumentType {
    BANK_STATEMENT, INVOICE, RECEIPT, TRIAL_BALANCE, DECLARATION, PAYROLL, UNCLASSIFIED
}
```

- [ ] **Step 2:** `intake/domain/DocumentStatus.java`:
```java
package ro.myfinance.intake.domain;

public enum DocumentStatus {
    UPLOADED
}
```

- [ ] **Step 3:** `intake/domain/DocumentSource.java`:
```java
package ro.myfinance.intake.domain;

public enum DocumentSource {
    REP, EMPLOYEE, EMAIL_AGENT
}
```

- [ ] **Step 4:** `intake/domain/Document.java`:
```java
package ro.myfinance.intake.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** An uploaded document for a company + period. Type is system-assigned by the classifier. */
@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected Document() {
    }

    public Document(UUID tenantId, UUID companyId, LocalDate periodMonth, DocumentType type,
                    DocumentSource source, DocumentStatus status, String originalFilename,
                    String contentType, long sizeBytes, String storageKey, UUID uploadedBy) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.periodMonth = periodMonth;
        this.type = type;
        this.source = source;
        this.status = status;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.uploadedBy = uploadedBy;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public LocalDate getPeriodMonth() { return periodMonth; }
    public DocumentType getType() { return type; }
    public DocumentSource getSource() { return source; }
    public DocumentStatus getStatus() { return status; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getStorageKey() { return storageKey; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
}
```

- [ ] **Step 5:** `intake/adapter/persistence/DocumentRepository.java`:
```java
package ro.myfinance.intake.adapter.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ro.myfinance.intake.domain.Document;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByCompanyIdOrderByUploadedAtDesc(UUID companyId);

    List<Document> findByCompanyIdAndPeriodMonthOrderByUploadedAtDesc(UUID companyId, LocalDate periodMonth);
}
```

- [ ] **Step 6:** Verify: `cd backend && mvn -B -DskipTests test-compile` → BUILD SUCCESS.

- [ ] **Step 7:** Commit:
```bash
git add backend/src/main/java/ro/myfinance/intake/domain/ backend/src/main/java/ro/myfinance/intake/adapter/persistence/
git commit -m "feat(intake): Document entity, enums, repository"
```

---

## Task 3: Storage port + adapters + factory

**Files:** Create `DocumentStorage`, `StoredObject` (application); `StorageProperties`, `LocalFsDocumentStorage`, `SupabaseDocumentStorage`, `DocumentStorageConfig` (adapter/external); test `LocalFsDocumentStorageTest`.

- [ ] **Step 1:** `intake/application/StoredObject.java`:
```java
package ro.myfinance.intake.application;

/** Result of persisting bytes to a storage backend. */
public record StoredObject(String key, long size) {
}
```

- [ ] **Step 2:** `intake/application/DocumentStorage.java`:
```java
package ro.myfinance.intake.application;

/** Port for binary document storage. Implementations: local filesystem (dev), Supabase Storage (prod). */
public interface DocumentStorage {

    StoredObject store(String key, byte[] bytes, String contentType);

    byte[] retrieve(String key);

    void delete(String key);
}
```

- [ ] **Step 3:** `intake/adapter/external/StorageProperties.java`:
```java
package ro.myfinance.intake.adapter.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Storage config. type=local uses the filesystem; type=supabase uses Supabase Storage. */
@ConfigurationProperties(prefix = "myfinance.storage")
public record StorageProperties(String type, String localBaseDir, String supabaseBucket) {

    public StorageProperties {
        if (type == null || type.isBlank()) {
            type = "local";
        }
        if (localBaseDir == null || localBaseDir.isBlank()) {
            localBaseDir = System.getProperty("java.io.tmpdir") + "/myfinance-docs";
        }
        if (supabaseBucket == null || supabaseBucket.isBlank()) {
            supabaseBucket = "documents";
        }
    }
}
```

- [ ] **Step 4:** `intake/adapter/external/LocalFsDocumentStorage.java`:
```java
package ro.myfinance.intake.adapter.external;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import ro.myfinance.intake.application.DocumentStorage;
import ro.myfinance.intake.application.StoredObject;

/** Stores documents on the local filesystem under a base directory. Dev/test default. */
public class LocalFsDocumentStorage implements DocumentStorage {

    private final Path baseDir;

    public LocalFsDocumentStorage(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    @Override
    public StoredObject store(String key, byte[] bytes, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return new StoredObject(key, bytes.length);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store document " + key, e);
        }
    }

    @Override
    public byte[] retrieve(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read document " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete document " + key, e);
        }
    }

    /** Resolve a key under baseDir, rejecting path traversal. */
    private Path resolve(String key) {
        Path resolved = baseDir.resolve(key).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Invalid storage key: " + key);
        }
        return resolved;
    }
}
```

- [ ] **Step 5:** `intake/adapter/external/SupabaseDocumentStorage.java`:
```java
package ro.myfinance.intake.adapter.external;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import ro.myfinance.common.config.SupabaseProperties;
import ro.myfinance.intake.application.DocumentStorage;
import ro.myfinance.intake.application.StoredObject;

/** Stores documents in a Supabase Storage bucket via the storage REST API. Prod adapter. */
public class SupabaseDocumentStorage implements DocumentStorage {

    private final RestClient client;
    private final String bucket;

    public SupabaseDocumentStorage(SupabaseProperties props, String bucket, RestClient.Builder builder) {
        this.bucket = bucket;
        this.client = builder
                .baseUrl(props.url())
                .defaultHeader("Authorization", "Bearer " + props.serviceRoleKey())
                .build();
    }

    @Override
    public StoredObject store(String key, byte[] bytes, String contentType) {
        client.post()
                .uri("/storage/v1/object/{bucket}/{key}", bucket, key)
                .header("x-upsert", "true")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes)
                .retrieve()
                .toBodilessEntity();
        return new StoredObject(key, bytes.length);
    }

    @Override
    public byte[] retrieve(String key) {
        return client.get()
                .uri("/storage/v1/object/{bucket}/{key}", bucket, key)
                .retrieve()
                .body(byte[].class);
    }

    @Override
    public void delete(String key) {
        client.delete()
                .uri("/storage/v1/object/{bucket}/{key}", bucket, key)
                .retrieve()
                .toBodilessEntity();
    }
}
```

- [ ] **Step 6:** `intake/adapter/external/DocumentStorageConfig.java`:
```java
package ro.myfinance.intake.adapter.external;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import ro.myfinance.common.config.SupabaseProperties;
import ro.myfinance.intake.application.DocumentStorage;

/**
 * Selects the storage adapter from config. type=supabase (and Supabase configured) → Supabase Storage;
 * otherwise local filesystem. Explicit factory (not @ConditionalOnProperty) so a blank/absent value
 * falls back cleanly to local.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class DocumentStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorageConfig.class);

    @Bean
    DocumentStorage documentStorage(StorageProperties props, SupabaseProperties supabase,
                                    RestClient.Builder builder) {
        if ("supabase".equalsIgnoreCase(props.type()) && supabase.isConfigured()) {
            log.info("Using Supabase Storage (bucket={})", props.supabaseBucket());
            return new SupabaseDocumentStorage(supabase, props.supabaseBucket(), builder);
        }
        log.info("Using local filesystem document storage (baseDir={})", props.localBaseDir());
        return new LocalFsDocumentStorage(Path.of(props.localBaseDir()));
    }
}
```

- [ ] **Step 7:** `backend/src/test/java/ro/myfinance/intake/LocalFsDocumentStorageTest.java`:
```java
package ro.myfinance.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.myfinance.intake.adapter.external.LocalFsDocumentStorage;
import ro.myfinance.intake.application.StoredObject;

class LocalFsDocumentStorageTest {

    @Test
    void storesRetrievesAndDeletes(@TempDir Path tmp) {
        LocalFsDocumentStorage storage = new LocalFsDocumentStorage(tmp);
        byte[] bytes = "hello pdf".getBytes(StandardCharsets.UTF_8);
        String key = "t1/c1/2026-06/doc-1-file.pdf";

        StoredObject stored = storage.store(key, bytes, "application/pdf");
        assertThat(stored.size()).isEqualTo(bytes.length);
        assertThat(storage.retrieve(key)).isEqualTo(bytes);

        storage.delete(key);
        assertThatThrownBy(() -> storage.retrieve(key)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsPathTraversal(@TempDir Path tmp) {
        LocalFsDocumentStorage storage = new LocalFsDocumentStorage(tmp);
        assertThatThrownBy(() -> storage.store("../escape.pdf", new byte[]{1}, "application/pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 8:** Verify: `cd backend && mvn -B test` → BUILD SUCCESS (new unit tests pass; ITs skip).

- [ ] **Step 9:** Commit:
```bash
git add backend/src/main/java/ro/myfinance/intake/application/ backend/src/main/java/ro/myfinance/intake/adapter/external/ backend/src/test/java/ro/myfinance/intake/LocalFsDocumentStorageTest.java
git commit -m "feat(intake): DocumentStorage port + local-FS & Supabase adapters + factory"
```

---

## Task 4: DocumentClassifier port + heuristic impl

**Files:** Create `DocumentClassifier` (application); `HeuristicDocumentClassifier` (adapter/external); test `HeuristicDocumentClassifierTest`.

- [ ] **Step 1:** `intake/application/DocumentClassifier.java`:
```java
package ro.myfinance.intake.application;

import ro.myfinance.intake.domain.DocumentType;

/** Determines a document's type from its bytes/metadata. Deterministic; no LLM. */
public interface DocumentClassifier {

    DocumentType classify(String filename, String contentType, byte[] bytes);
}
```

- [ ] **Step 2:** `intake/adapter/external/HeuristicDocumentClassifier.java`:
```java
package ro.myfinance.intake.adapter.external;

import java.io.IOException;
import java.text.Normalizer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.myfinance.intake.application.DocumentClassifier;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Deterministic, best-effort document classifier. Images → RECEIPT; PDFs classified by embedded XML
 * (declarations) and Romanian text markers. Anything unrecognized → UNCLASSIFIED. Never throws.
 */
@Component
public class HeuristicDocumentClassifier implements DocumentClassifier {

    private static final Logger log = LoggerFactory.getLogger(HeuristicDocumentClassifier.class);

    @Override
    public DocumentType classify(String filename, String contentType, byte[] bytes) {
        if (contentType != null && contentType.toLowerCase().startsWith("image/")) {
            return DocumentType.RECEIPT;
        }
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            if (hasEmbeddedXml(pdf)) {
                return DocumentType.DECLARATION;
            }
            String text = normalize(extractText(pdf));
            if (containsAny(text, "a.n.a.f", "anaf", "declarat", "d212", "d300", "d301", "d112")) {
                return DocumentType.DECLARATION;
            }
            if (containsAny(text, "extras de cont", "brd", "banca transilvania", "bcr", "ing bank", "raiffeisen")) {
                return DocumentType.BANK_STATEMENT;
            }
            if (containsAny(text, "factur", "invoice")) {
                return DocumentType.INVOICE;
            }
            if (containsAny(text, "balanta")) {
                return DocumentType.TRIAL_BALANCE;
            }
            return DocumentType.UNCLASSIFIED;
        } catch (IOException | RuntimeException e) {
            log.debug("Classification failed for {}, defaulting to UNCLASSIFIED", filename, e);
            return DocumentType.UNCLASSIFIED;
        }
    }

    private String extractText(PDDocument pdf) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(1);
        stripper.setEndPage(Math.min(3, Math.max(1, pdf.getNumberOfPages())));
        return stripper.getText(pdf);
    }

    private boolean hasEmbeddedXml(PDDocument pdf) {
        try {
            PDDocumentCatalog catalog = pdf.getDocumentCatalog();
            PDDocumentNameDictionary names = catalog.getNames();
            if (names == null) {
                return false;
            }
            PDEmbeddedFilesNameTreeNode tree = names.getEmbeddedFiles();
            if (tree == null || tree.getNames() == null) {
                return false;
            }
            return tree.getNames().keySet().stream()
                    .anyMatch(n -> n != null && n.toLowerCase().endsWith(".xml"));
        } catch (IOException e) {
            return false;
        }
    }

    /** Lower-cased, diacritics-stripped, for accent-insensitive matching. */
    private String normalize(String s) {
        if (s == null) {
            return "";
        }
        String stripped = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return stripped.toLowerCase();
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 3:** `backend/src/test/java/ro/myfinance/intake/HeuristicDocumentClassifierTest.java`:
```java
package ro.myfinance.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import ro.myfinance.intake.adapter.external.HeuristicDocumentClassifier;
import ro.myfinance.intake.domain.DocumentType;

class HeuristicDocumentClassifierTest {

    private final HeuristicDocumentClassifier classifier = new HeuristicDocumentClassifier();

    private byte[] pdfWithText(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void imageContentTypeIsReceipt() {
        assertThat(classifier.classify("photo.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                .isEqualTo(DocumentType.RECEIPT);
    }

    @Test
    void bankStatementByText() throws Exception {
        assertThat(classifier.classify("x.pdf", "application/pdf", pdfWithText("Extras de cont BRD")))
                .isEqualTo(DocumentType.BANK_STATEMENT);
    }

    @Test
    void invoiceByText() throws Exception {
        assertThat(classifier.classify("x.pdf", "application/pdf", pdfWithText("FACTURA fiscala nr 123")))
                .isEqualTo(DocumentType.INVOICE);
    }

    @Test
    void trialBalanceByText() throws Exception {
        assertThat(classifier.classify("x.pdf", "application/pdf", pdfWithText("Balanta de verificare")))
                .isEqualTo(DocumentType.TRIAL_BALANCE);
    }

    @Test
    void unmarkedPdfIsUnclassified() throws Exception {
        assertThat(classifier.classify("x.pdf", "application/pdf", pdfWithText("Lorem ipsum dolor")))
                .isEqualTo(DocumentType.UNCLASSIFIED);
    }

    @Test
    void garbageBytesAreUnclassified() {
        assertThat(classifier.classify("x.pdf", "application/pdf", new byte[]{9, 9, 9}))
                .isEqualTo(DocumentType.UNCLASSIFIED);
    }
}
```

- [ ] **Step 4:** Verify: `cd backend && mvn -B test` → BUILD SUCCESS (classifier tests pass).

- [ ] **Step 5:** Commit:
```bash
git add backend/src/main/java/ro/myfinance/intake/application/DocumentClassifier.java backend/src/main/java/ro/myfinance/intake/adapter/external/HeuristicDocumentClassifier.java backend/src/test/java/ro/myfinance/intake/HeuristicDocumentClassifierTest.java
git commit -m "feat(intake): DocumentClassifier port + heuristic PDFBox impl"
```

---

## Task 5: DocumentService + IT

**Files:** Create `DocumentService` (application); test `DocumentServiceIT`.

- [ ] **Step 1:** `intake/application/DocumentService.java`:
```java
package ro.myfinance.intake.application;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.intake.adapter.persistence.DocumentRepository;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DocumentSource;
import ro.myfinance.intake.domain.DocumentStatus;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Document intake: classify, store, and manage uploaded documents. Tenant-scoped via RLS; type is
 * system-assigned. Staff-facing (authorization enforced at the controller).
 */
@Service
@Transactional
public class DocumentService {

    static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;
    static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("application/pdf", "image/png", "image/jpeg", "image/webp");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final CompanyRepository companies;
    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final DocumentClassifier classifier;
    private final AuditRecorder audit;

    public DocumentService(CompanyRepository companies, DocumentRepository documents,
                           DocumentStorage storage, DocumentClassifier classifier, AuditRecorder audit) {
        this.companies = companies;
        this.documents = documents;
        this.storage = storage;
        this.classifier = classifier;
        this.audit = audit;
    }

    public Document upload(UUID companyId, LocalDate periodMonth, String filename,
                           String contentType, byte[] bytes) {
        validate(contentType, bytes);
        UUID tenantId = currentTenant();
        companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));

        LocalDate period = periodMonth.withDayOfMonth(1);
        DocumentType type = classifier.classify(filename, contentType, bytes);
        String safeName = sanitize(filename);
        UUID id = UUID.randomUUID();
        String key = "%s/%s/%s/%s-%s".formatted(tenantId, companyId, period.format(MONTH), id, safeName);

        storage.store(key, bytes, contentType);
        UUID uploadedBy = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        Document doc = new Document(tenantId, companyId, period, type, DocumentSource.EMPLOYEE,
                DocumentStatus.UPLOADED, filename, contentType, bytes.length, key, uploadedBy);
        Document saved = documents.save(doc);
        audit.record("DOCUMENT_UPLOADED", "document", saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Document> list(UUID companyId, LocalDate periodMonth) {
        return periodMonth == null
                ? documents.findByCompanyIdOrderByUploadedAtDesc(companyId)
                : documents.findByCompanyIdAndPeriodMonthOrderByUploadedAtDesc(companyId, periodMonth.withDayOfMonth(1));
    }

    @Transactional(readOnly = true)
    public DocumentContent getContent(UUID id) {
        Document doc = require(id);
        return new DocumentContent(doc, storage.retrieve(doc.getStorageKey()));
    }

    public void delete(UUID id) {
        Document doc = require(id);
        storage.delete(doc.getStorageKey());
        documents.delete(doc);
        audit.record("DOCUMENT_DELETED", "document", id);
    }

    private Document require(UUID id) {
        return documents.findById(id)
                .orElseThrow(() -> new NotFoundException("Document not found: " + id));
    }

    private void validate(String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Empty file");
        }
        if (bytes.length > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("File exceeds 20 MB limit");
        }
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("Unsupported file type: " + contentType);
        }
    }

    private String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            return "file";
        }
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private UUID currentTenant() {
        return TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to request"));
    }

    public record DocumentContent(Document document, byte[] bytes) {
    }
}
```
NOTE: `TenantContext` exposes `current()`, `tenantId()`, `companyId()` (all `Optional`) and the `Identity(tenantId, userId, role, companyId)` record — there is no `userId()` shortcut, so the `uploadedBy` line uses `TenantContext.current().map(TenantContext.Identity::userId).orElse(null)` (already correct above).

- [ ] **Step 2:** `backend/src/test/java/ro/myfinance/intake/DocumentServiceIT.java`:
```java
package ro.myfinance.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.application.CompanyService;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.support.AbstractPostgresIT;

class DocumentServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000d1");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000d2");

    @Autowired DocumentService documents;
    @Autowired CompanyService companies;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    private UUID asTenantWithCompany(UUID tenantId) {
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STANDARD') on conflict do nothing",
                tenantId, "T-" + tenantId);
        return companies.create("Client SRL", "RO-DOC-" + UUID.randomUUID(), "SRL", "Cluj", null, null, null).getId();
    }

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 1, 2, 3};
    }

    @Test
    void uploadsClassifiesListsAndDownloads() {
        UUID companyId = asTenantWithCompany(TENANT_A);

        Document doc = documents.upload(companyId, LocalDate.of(2026, 6, 15), "receipt.jpg", "image/jpeg", png());

        assertThat(doc.getType().name()).isEqualTo("RECEIPT");
        assertThat(doc.getPeriodMonth()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(documents.list(companyId, null)).hasSize(1);
        assertThat(documents.getContent(doc.getId()).bytes()).isEqualTo(png());
    }

    @Test
    void rejectsUnsupportedType() {
        UUID companyId = asTenantWithCompany(TENANT_A);
        assertThatThrownBy(() ->
                documents.upload(companyId, LocalDate.of(2026, 6, 1), "x.exe", "application/octet-stream", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteRemovesDocument() {
        UUID companyId = asTenantWithCompany(TENANT_A);
        Document doc = documents.upload(companyId, LocalDate.of(2026, 6, 1), "r.png", "image/png", png());
        documents.delete(doc.getId());
        assertThat(documents.list(companyId, null)).isEmpty();
    }

    @Test
    void tenantBCannotSeeOrDeleteTenantADocuments() {
        UUID companyA = asTenantWithCompany(TENANT_A);
        Document docA = documents.upload(companyA, LocalDate.of(2026, 6, 1), "a.png", "image/png", png());

        asTenantWithCompany(TENANT_B);
        assertThat(documents.list(companyA, null)).isEmpty();          // RLS hides A's company docs
        assertThatThrownBy(() -> documents.delete(docA.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> documents.getContent(docA.getId())).isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 3:** Verify: `cd backend && mvn -B test` → BUILD SUCCESS (DocumentServiceIT skips without Docker; unit tests pass). Also `mvn -B -DskipTests test-compile` must pass — if `TenantContext.userId()` doesn't exist, fix that one line per the NOTE in Step 1.

- [ ] **Step 4:** Commit:
```bash
git add backend/src/main/java/ro/myfinance/intake/application/DocumentService.java backend/src/test/java/ro/myfinance/intake/DocumentServiceIT.java
git commit -m "feat(intake): DocumentService (upload/classify/list/download/delete) + IT"
```

---

## Task 6: DocumentController + DTOs

**Files:** Create `DocumentDtos`, `DocumentController` (adapter/web).

- [ ] **Step 1:** `intake/adapter/web/DocumentDtos.java`:
```java
package ro.myfinance.intake.adapter.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import ro.myfinance.intake.domain.Document;

public final class DocumentDtos {

    private DocumentDtos() {
    }

    public record DocumentResponse(UUID id, String type, String status, String originalFilename,
                                   String contentType, long sizeBytes, LocalDate periodMonth,
                                   UUID uploadedBy, Instant uploadedAt) {
        public static DocumentResponse from(Document d) {
            return new DocumentResponse(d.getId(), d.getType().name(), d.getStatus().name(),
                    d.getOriginalFilename(), d.getContentType(), d.getSizeBytes(), d.getPeriodMonth(),
                    d.getUploadedBy(), d.getUploadedAt());
        }
    }
}
```

- [ ] **Step 2:** `intake/adapter/web/DocumentController.java`:
```java
package ro.myfinance.intake.adapter.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ro.myfinance.intake.adapter.web.DocumentDtos.DocumentResponse;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.application.DocumentService.DocumentContent;

/** Document intake. Firm staff (admin/employee) only. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/documents")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse upload(@PathVariable UUID companyId,
                                   @RequestParam("periodMonth") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodMonth,
                                   @RequestParam("file") MultipartFile file) {
        try {
            return DocumentResponse.from(service.upload(companyId, periodMonth,
                    file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
    }

    @GetMapping
    public List<DocumentResponse> list(@PathVariable UUID companyId,
                                       @RequestParam(value = "period", required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        return service.list(companyId, period).stream().map(DocumentResponse::from).toList();
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID companyId, @PathVariable UUID id) {
        DocumentContent c = service.getContent(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(c.document().getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + c.document().getOriginalFilename() + "\"")
                .body(c.bytes());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID companyId, @PathVariable UUID id) {
        service.delete(id);
    }
}
```

- [ ] **Step 3:** Verify: `cd backend && mvn -B test` → BUILD SUCCESS.

- [ ] **Step 4:** Commit:
```bash
git add backend/src/main/java/ro/myfinance/intake/adapter/web/
git commit -m "feat(intake): document upload/list/download/delete REST endpoints"
```

---

## Task 7: Frontend API client (multipart + blob) + documents module

**Files:** Modify `frontend/src/lib/apiClient.ts`; create `frontend/src/api/documents.ts`.

- [ ] **Step 1:** Add two helpers to `frontend/src/lib/apiClient.ts` (after the existing `api` function). They reuse the same auth + error handling but suit multipart upload and binary download:
```ts
/** Resolves the current Supabase access token, or null. */
async function authToken(): Promise<string | null> {
  const { data } = await supabase.auth.getSession();
  return data.session?.access_token ?? null;
}

/** POST multipart/form-data (browser sets the boundary; do NOT set Content-Type). */
export async function upload<T>(path: string, form: FormData): Promise<T> {
  const token = await authToken();
  const headers = new Headers();
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const res = await fetch(`${BASE_URL}${path}`, { method: "POST", body: form, headers });
  if (!res.ok) {
    let detail: unknown;
    try {
      detail = await res.json();
    } catch {
      detail = await res.text();
    }
    const message =
      detail && typeof detail === "object" && "detail" in detail
        ? String((detail as { detail: unknown }).detail)
        : `Upload failed (${res.status})`;
    throw new ApiError(res.status, message, detail);
  }
  return (await res.json()) as T;
}

/** GET a binary resource as a Blob (for downloads). */
export async function download(path: string): Promise<Blob> {
  const token = await authToken();
  const headers = new Headers();
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const res = await fetch(`${BASE_URL}${path}`, { headers });
  if (!res.ok) {
    throw new ApiError(res.status, `Download failed (${res.status})`);
  }
  return res.blob();
}
```

- [ ] **Step 2:** Create `frontend/src/api/documents.ts`:
```ts
import { api, upload, download } from "../lib/apiClient";

export interface Document {
  id: string;
  type: string;
  status: string;
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
  periodMonth: string;
  uploadedBy: string | null;
  uploadedAt: string;
}

export const documentsApi = {
  list: (companyId: string, period?: string) =>
    api<Document[]>(
      `/api/v1/companies/${companyId}/documents${period ? `?period=${period}` : ""}`,
    ),
  upload: (companyId: string, periodMonth: string, file: File) => {
    const form = new FormData();
    form.append("file", file);
    form.append("periodMonth", periodMonth);
    return upload<Document>(`/api/v1/companies/${companyId}/documents`, form);
  },
  download: (companyId: string, id: string) =>
    download(`/api/v1/companies/${companyId}/documents/${id}/content`),
  remove: (companyId: string, id: string) =>
    api<void>(`/api/v1/companies/${companyId}/documents/${id}`, { method: "DELETE" }),
};
```

- [ ] **Step 3:** Verify: `cd frontend && npx tsc -b` → no errors.

- [ ] **Step 4:** Commit:
```bash
git add frontend/src/lib/apiClient.ts frontend/src/api/documents.ts
git commit -m "feat(fe): apiClient multipart upload + blob download; documents API client"
```

---

## Task 8: Frontend Statements page + i18n + route

**Files:** Create `frontend/src/pages/Statements.tsx`; modify `frontend/src/i18n.ts`, `frontend/src/App.tsx`.

- [ ] **Step 1:** Add i18n keys to `frontend/src/i18n.ts`. In `ro.translation` (after `common.save`):
```ts
      "documents.title": "Documente",
      "documents.upload": "Încarcă document",
      "documents.company": "Firmă",
      "documents.period": "Perioadă",
      "documents.file": "Fișier",
      "documents.type": "Tip",
      "documents.filename": "Nume fișier",
      "documents.uploadedAt": "Încărcat la",
      "documents.download": "Descarcă",
      "documents.empty": "Niciun document încă.",
      "documentType.BANK_STATEMENT": "Extras de cont",
      "documentType.INVOICE": "Factură",
      "documentType.RECEIPT": "Bon/Chitanță",
      "documentType.TRIAL_BALANCE": "Balanță",
      "documentType.DECLARATION": "Declarație",
      "documentType.PAYROLL": "Salarizare",
      "documentType.UNCLASSIFIED": "Neclasificat",
```
In `en.translation` (after `common.save`):
```ts
      "documents.title": "Documents",
      "documents.upload": "Upload document",
      "documents.company": "Company",
      "documents.period": "Period",
      "documents.file": "File",
      "documents.type": "Type",
      "documents.filename": "File name",
      "documents.uploadedAt": "Uploaded at",
      "documents.download": "Download",
      "documents.empty": "No documents yet.",
      "documentType.BANK_STATEMENT": "Bank statement",
      "documentType.INVOICE": "Invoice",
      "documentType.RECEIPT": "Receipt",
      "documentType.TRIAL_BALANCE": "Trial balance",
      "documentType.DECLARATION": "Declaration",
      "documentType.PAYROLL": "Payroll",
      "documentType.UNCLASSIFIED": "Unclassified",
```

- [ ] **Step 2:** Create `frontend/src/pages/Statements.tsx`:
```tsx
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { documentsApi } from "../api/documents";
import { ApiError } from "../lib/apiClient";
import { Field } from "../components/Field";

/** Statements & invoices — document intake (staff). Upload, list, download, delete. */
export function Statements() {
  const { t } = useTranslation();
  const companies = useQuery({ queryKey: ["companies"], queryFn: companiesApi.list });
  const [companyId, setCompanyId] = useState("");
  // default period = first day of the current month, yyyy-MM-dd
  const [period, setPeriod] = useState(() => new Date().toISOString().slice(0, 7) + "-01");

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="card">
        <h1 style={{ marginTop: 0 }}>{t("documents.title")}</h1>
        <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
          <Field label={t("documents.company")}>
            <select value={companyId} onChange={(e) => setCompanyId(e.target.value)}>
              <option value="">—</option>
              {(companies.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>{c.legalName}</option>
              ))}
            </select>
          </Field>
          <Field label={t("documents.period")}>
            <input
              type="month"
              value={period.slice(0, 7)}
              onChange={(e) => setPeriod(e.target.value + "-01")}
            />
          </Field>
        </div>
      </div>

      {companyId && <UploadCard companyId={companyId} period={period} />}
      {companyId && <DocumentsTable companyId={companyId} period={period} />}
    </div>
  );
}

function UploadCard({ companyId, period }: { companyId: string; period: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);

  const mutation = useMutation({
    mutationFn: () => documentsApi.upload(companyId, period, file!),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["documents", companyId, period] });
      setFile(null);
      setError(null);
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Upload failed"),
  });

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("documents.upload")}</h2>
      <form
        style={{ display: "flex", gap: 8, alignItems: "center" }}
        onSubmit={(e) => { e.preventDefault(); if (file) mutation.mutate(); }}
      >
        <input
          type="file"
          accept="application/pdf,image/png,image/jpeg,image/webp"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        />
        <button className="primary" type="submit" disabled={!file || mutation.isPending}>
          {mutation.isPending ? "…" : t("documents.upload")}
        </button>
      </form>
      {error && <p style={{ color: "#dc2626" }}>{error}</p>}
    </div>
  );
}

function DocumentsTable({ companyId, period }: { companyId: string; period: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data = [], isLoading } = useQuery({
    queryKey: ["documents", companyId, period],
    queryFn: () => documentsApi.list(companyId, period),
  });

  const remove = useMutation({
    mutationFn: (id: string) => documentsApi.remove(companyId, id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["documents", companyId, period] }),
  });

  const handleDownload = async (id: string, filename: string) => {
    const blob = await documentsApi.download(companyId, id);
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="card">
      {isLoading ? (
        <p>{t("common.loading")}</p>
      ) : (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8 }}>{t("documents.filename")}</th>
              <th style={{ padding: 8 }}>{t("documents.type")}</th>
              <th style={{ padding: 8 }}>{t("documents.uploadedAt")}</th>
              <th style={{ padding: 8 }} />
            </tr>
          </thead>
          <tbody>
            {data.map((d) => (
              <tr key={d.id} style={{ borderTop: "1px solid var(--border)" }}>
                <td style={{ padding: 8 }}>{d.originalFilename}</td>
                <td style={{ padding: 8 }}>{t(`documentType.${d.type}`, { defaultValue: d.type })}</td>
                <td style={{ padding: 8 }}>{new Date(d.uploadedAt).toLocaleString()}</td>
                <td style={{ padding: 8, whiteSpace: "nowrap" }}>
                  <button onClick={() => void handleDownload(d.id, d.originalFilename)}>
                    {t("documents.download")}
                  </button>{" "}
                  <button
                    onClick={() => remove.mutate(d.id)}
                    disabled={remove.isPending}
                    style={{ color: "#dc2626", border: "none", background: "none", cursor: "pointer" }}
                  >
                    ✕
                  </button>
                </td>
              </tr>
            ))}
            {data.length === 0 && (
              <tr>
                <td colSpan={4} style={{ padding: 8, color: "var(--text-muted)" }}>
                  {t("documents.empty")}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      )}
    </div>
  );
}
```

- [ ] **Step 3:** Swap the placeholder route in `frontend/src/App.tsx`. Add the import near the other page imports:
```tsx
import { Statements } from "./pages/Statements";
```
Replace the line:
```tsx
          <Route path="/statements" element={<PagePlaceholder title="Statements & invoices" module="MOD-04/05" />} />
```
with:
```tsx
          <Route path="/statements" element={<Statements />} />
```

- [ ] **Step 4:** Verify: `cd frontend && npm run lint && npm run build` → both succeed.

- [ ] **Step 5:** Commit:
```bash
git add frontend/src/pages/Statements.tsx frontend/src/i18n.ts frontend/src/App.tsx
git commit -m "feat(fe): Statements page — document upload/list/download/delete + i18n"
```

---

## Task 9: Final verification

- [ ] **Step 1:** Backend full suite: `cd backend && mvn -B test` → BUILD SUCCESS (unit tests pass; ITs skip without Docker).
- [ ] **Step 2:** Frontend: `cd frontend && npm run lint && npm run build` → both clean.
- [ ] **Step 3:** No commit needed unless fixes were made; if so, commit them with a clear message.

---

## Self-review

**Spec coverage:**
- ✅ `document` table + RLS — Task 1
- ✅ DocumentStorage port + local FS + Supabase + factory — Task 3
- ✅ DocumentClassifier port + heuristic (images/PDF markers/embedded XML/UNCLASSIFIED) — Task 4
- ✅ DocumentService upload/classify/list/download/delete + validation (20MB, allowed types) + audit — Task 5
- ✅ Endpoints (multipart upload, list, content download, delete), staff-only — Task 6
- ✅ Frontend page (file-only upload, detected type, download, delete) + multipart/blob client — Tasks 7, 8
- ✅ Tests: classifier unit, local-FS unit, DocumentServiceIT (round-trip + cross-tenant) — Tasks 3,4,5
- ✅ PDFBox dependency — Task 1

**Type consistency:** `DocumentService.upload(companyId, periodMonth, filename, contentType, bytes)` ↔ controller multipart ↔ `documentsApi.upload`. `DocumentContent(document, bytes)` record used by controller download. `documentsApi` methods match controller routes. `DocumentType` enum names ↔ `documentType.*` i18n keys ↔ classifier outputs.

**Open risk flagged in plan:** `TenantContext` user-id accessor name (Task 5 Step 1 NOTE) — verify the exact method when implementing.
