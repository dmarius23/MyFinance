# Document Intake (Statements & Invoices — Slice A) — Design Spec

**Date:** 2026-06-03
**Module:** new **Intake** module (`ro.myfinance.intake`) — first slice of MOD-04/05 ("Statements & Invoices")
**Status:** Approved for planning

## 1. Goal & scope

First, foundational slice of the Statements & Invoices area: let firm staff **upload documents for a company + period**, have the **system classify the document type** (no manual tagging), store the file behind a storage port, and **list / download / delete** documents.

**In scope**
- `document` table + RLS.
- Upload (multipart) → classify → store → persist; list (by company, optional period); download; delete (audited).
- `DocumentStorage` port with a **local-filesystem** adapter (dev/test) and a **Supabase Storage** adapter (prod), selected by config.
- `DocumentClassifier` port with a deterministic **heuristic** implementation (no LLM).
- `/statements` frontend page (staff): company + period pickers, upload (file only), documents table with detected type, download, delete.

**Out of scope (later slices)**
- Any real extraction/parsing into `bank_statement` / `bank_transaction` / `extraction_job`.
- Reconciliation, document-requirement classification, period status.
- Representative self-upload (portal), email-ingestion agent.
- Re-classification workflow / manual type override UI (the classifier owns type for now; UNCLASSIFIED is allowed).

## 2. Decisions (locked during brainstorming)
- **First slice:** Document Intake (foundation for all of MOD-04/05).
- **Storage:** `DocumentStorage` port — local FS for dev/test, Supabase Storage for prod.
- **Upload actors:** firm staff only (TENANT_ADMIN, EMPLOYEE). Rep self-upload is a later slice.
- **Type:** determined by the system at upload (deterministic classifier), **not** chosen by the user.

## 3. Data model (Flyway `V4__document.sql`)

```sql
CREATE TABLE document (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenant(id),
    company_id        uuid NOT NULL REFERENCES company(id),
    period_month      date NOT NULL,                 -- normalized to the 1st
    type              text NOT NULL,                 -- DocumentType (incl. UNCLASSIFIED)
    source            text NOT NULL,                 -- REP | EMPLOYEE | EMAIL_AGENT (staff upload → EMPLOYEE)
    status            text NOT NULL,                 -- UPLOADED (only state this slice)
    original_filename text NOT NULL,
    content_type      text NOT NULL,
    size_bytes        bigint NOT NULL,
    storage_key       text NOT NULL,
    uploaded_by       uuid,                          -- app_user id (JWT subject)
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
- Local DB already past `V1000` (seed); `out-of-order: true` (local profile) lets `V4` apply. CI/test harness uses only `db/migration`, so `V4` is in order there.

## 4. Backend module `ro.myfinance.intake`

```
intake/
 ├─ domain/        Document, DocumentType, DocumentStatus, DocumentSource
 ├─ application/   DocumentService, DocumentStorage (port), StoredObject (record),
 │                 DocumentClassifier (port)
 └─ adapter/
     ├─ persistence/  DocumentRepository
     ├─ web/          DocumentController, DocumentDtos
     └─ external/     LocalFsDocumentStorage, SupabaseDocumentStorage, DocumentStorageConfig,
                      HeuristicDocumentClassifier
```

### 4.1 Enums
- `DocumentType`: `BANK_STATEMENT, INVOICE, RECEIPT, TRIAL_BALANCE, DECLARATION, PAYROLL, UNCLASSIFIED`.
- `DocumentStatus`: `UPLOADED` (only state this slice; extraction states added later).
- `DocumentSource`: `REP, EMPLOYEE, EMAIL_AGENT`.

### 4.2 `DocumentStorage` port + adapters
- Port: `StoredObject store(String key, byte[] bytes, String contentType)`, `byte[] retrieve(String key)`, `void delete(String key)`. `StoredObject` record: `(String key, long size)`.
- **Key scheme:** `{tenantId}/{companyId}/{yyyy-MM}/{documentId}-{sanitizedFilename}`.
- `LocalFsDocumentStorage`: writes under `myfinance.storage.local.base-dir` (default `${java.io.tmpdir}/myfinance-docs`); creates parent dirs; path-traversal-safe (sanitize key).
- `SupabaseDocumentStorage`: Supabase Storage REST (`/storage/v1/object/{bucket}/{key}`) with service-role bearer; bucket from `myfinance.storage.supabase.bucket`.
- `DocumentStorageConfig`: `@Configuration` factory bean — `myfinance.storage.type` (`local` default | `supabase`); mirrors the `RepresentativeInviterConfig` pattern (explicit factory, not `@ConditionalOnProperty`).

### 4.3 `DocumentClassifier` port + heuristic impl (deterministic, no LLM)
- Port: `DocumentType classify(String filename, String contentType, byte[] bytes)`.
- `HeuristicDocumentClassifier` (uses **Apache PDFBox** — added as a dependency):
  1. `contentType` starts with `image/` → `RECEIPT`.
  2. PDF: if it has an **embedded `.xml`** file (PDFBox embedded-files name tree) **or** text matches ANAF/declaration markers (`a.n.a.f`, `declarat`, `d212`, `d300`, `d301`, `d112`) → `DECLARATION`.
  3. PDF text (diacritics-insensitive, lower-cased) contains `extras de cont` or a known bank token (`brd`, `banca transilvania`, `bcr`, `ing`, `raiffeisen`) → `BANK_STATEMENT`.
  4. contains `factur` or `invoice` → `INVOICE`.
  5. contains `balanta` (or `balanță`) → `TRIAL_BALANCE`.
  6. else → `UNCLASSIFIED`.
  - PDF text read via PDFBox `PDFTextStripper` over the first ~3 pages; any parse error → `UNCLASSIFIED` (never throws to the caller). Heuristics now; tuned against real fixtures in the extraction slices.

### 4.4 `DocumentService` (tenant-scoped via `TenantContext`, audited)
- `Document upload(UUID companyId, LocalDate periodMonth, String filename, String contentType, byte[] bytes)`:
  - verify the company is visible (`CompanyRepository.findById` under RLS → 404 if not);
  - `type = classifier.classify(...)`; build id; `key = storageKey(...)`; `storage.store(key, bytes, contentType)`;
  - persist `document` (source `EMPLOYEE`, status `UPLOADED`, `uploaded_by` = current user); `audit.record("DOCUMENT_UPLOADED", "document", id)`.
- `List<Document> list(UUID companyId, LocalDate periodMonth /*nullable*/)`.
- `record DocumentContent(Document document, byte[] bytes) getContent(UUID id)` (404 if not visible).
- `void delete(UUID id)`: load (404 if not visible), `storage.delete(key)`, delete row, `audit.record("DOCUMENT_DELETED", ...)`.
- File validation (in service or controller): max **20 MB**; allowed content types: `application/pdf`, `image/png`, `image/jpeg`, `image/webp`. Reject others → 400.

## 5. API (`@PreAuthorize("hasAnyRole('TENANT_ADMIN','EMPLOYEE')")`)
- `POST /api/v1/companies/{companyId}/documents` — multipart form: `file` (required), `periodMonth` (`yyyy-MM-dd`, required) → 201 + `DocumentResponse`.
- `GET /api/v1/companies/{companyId}/documents?period=yyyy-MM-dd` — list (period optional).
- `GET /api/v1/companies/{companyId}/documents/{id}/content` — streams bytes with `Content-Type` + `Content-Disposition: attachment; filename=...`.
- `DELETE /api/v1/companies/{companyId}/documents/{id}` — 204.
- `DocumentResponse`: `(id, type, status, originalFilename, contentType, sizeBytes, periodMonth, uploadedBy, uploadedAt)`.

## 6. Frontend (`/statements` — replaces the placeholder)
- `api/documents.ts`: typed client. Add an **`upload` helper** to `apiClient` that sends `FormData` (no manual `Content-Type`, so the browser sets the multipart boundary) while still attaching the Supabase auth header; download via a fetch that returns a blob.
- `pages/Statements.tsx`: company dropdown + period (month) picker; an upload control (**file only** — no type field) that posts to the selected company+period; a documents table (filename, **detected type** translated, period, size, uploaded-at, **Download**, **Delete**).
- i18n: `nav.statements` already exists; add `documents.*` + `documentType.*` labels (RO/EN), incl. an `UNCLASSIFIED` label (RO "Neclasificat").
- Route `/statements` already exists under the staff guard; swap the placeholder for `Statements`.

## 7. Testing
- **`HeuristicDocumentClassifierTest`** (unit, no Docker): build small in-memory PDFs with PDFBox containing marker text → assert `BANK_STATEMENT` ("Extras de cont"), `INVOICE` ("Factura"), `TRIAL_BALANCE` ("Balanta de verificare"); `image/png` bytes → `RECEIPT`; unmarked text → `UNCLASSIFIED`.
- **`LocalFsDocumentStorageTest`** (unit, `@TempDir`): store → retrieve (bytes equal) → delete (gone).
- **`DocumentServiceIT`** (Testcontainers, skips without Docker): upload→list→getContent→delete round-trip via the local-FS adapter (temp dir); **cross-tenant isolation** — tenant B cannot list/get/delete tenant A's documents.
- Frontend: `npm run lint && npm run build` green.

## 8. Dependencies
- Add **Apache PDFBox** (`org.apache.pdfbox:pdfbox`, 3.0.x) to `backend/pom.xml` (classifier; reused by later extraction slices). No iText (license rule).

## 9. Build order
1. `V4` migration (document table + RLS).
2. PDFBox dependency.
3. Intake domain (enums, `Document`) + `DocumentRepository`.
4. `DocumentStorage` port + `LocalFsDocumentStorage` (+ unit test) + `SupabaseDocumentStorage` + `DocumentStorageConfig`.
5. `DocumentClassifier` port + `HeuristicDocumentClassifier` (+ unit test).
6. `DocumentService` + `DocumentServiceIT`.
7. `DocumentController` + `DocumentDtos`.
8. Frontend: `apiClient` upload/download helpers + `api/documents.ts`.
9. Frontend: `Statements` page + i18n + route swap.
10. Verify (backend tests, frontend lint+build).
