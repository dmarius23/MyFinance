# MyFinance — Cloud-folder document ingestion (design note v1)

**Status:** Draft for review · **Scope:** folder ingestion only (email monitoring parked) · **Owner:** TBD

## 1. Goal

Let accountants stop manually uploading documents. Instead, the app watches a **per-tenant cloud folder** (Google Drive first, OneDrive later) and pulls new documents into MyFinance automatically, filed against the correct **client company** and **month**. First target is **payroll**, organised as `company / [month] / payroll files`; the same machinery generalises to bank statements/invoices and to a future **email** connector.

Non-goals (v1): on-prem agent, true local-disk watching, push/webhooks, OneDrive (designed for, implemented later), email monitoring (separate later phase).

## 2. Key decisions

| Decision | Choice | Rationale |
|---|---|---|
| Sources v1 | Google Drive (OneDrive next) | User's folder lives in Drive; OneDrive shares the port |
| Where it runs | Cloud (existing worker), **no on-prem agent** | Cloud-to-cloud; nothing local to reach |
| Trigger | **Scheduled poll** (incremental) | Simple, robust, no public webhook surface |
| Multi-tenant | One `source_connection` per tenant; each tenant brings its own Drive/OneDrive | Tenant isolation via RLS |
| Company mapping | **Explicit per-company folder** + folder-name (name/CUI) convention | Survives renames; convention as the zero-config default |
| Folder layout | `root / <company name or CUI> / [YYYY-MM] / files` | Matches how the accountant already organises payroll |
| Period | Month subfolder if present, else document content-date, else file modified-date | Reps/accountants file last month's docs late |
| Doc type | This structure ⇒ `forcedType=PAYROLL`; other folders/connectors use the auto-classifier | Payroll files don't auto-classify reliably |
| Safety | Wrong-party guard + **Intake-review queue** for anything unresolved | Never silently misfile |

## 3. Architecture

Everything funnels into the **existing single intake chokepoint** — `DocumentService.upload(companyId, period, filename, contentType, bytes, forcedType, source)` → which emits `DocumentUploadedEvent` → existing extraction/payroll/reconciliation listeners. This feature only adds **acquisition adapters** in front of that method; the downstream pipeline is untouched.

```
Google Drive / OneDrive folder
        │  (scheduled poll, incremental)
        ▼
CloudFolderConnector (provider adapter)         ── cloud worker (pgmq job per connection)
        │  listChanges(since) → download(file)
        ▼
Mapping engine  (folder→company, subfolder→month, content-date refine, wrong-party guard)
        │
        ├─ resolved → DocumentService.upload(..., forcedType=PAYROLL, source=DRIVE) → events
        └─ unresolved/flagged → Intake-review queue
        │
        ▼
import_file ledger (idempotency + provenance)
```

**Port:**
```java
interface CloudFolderConnector {
    Provider provider();                                  // GOOGLE_DRIVE | ONEDRIVE
    List<RemoteFile> listChanges(Connection c, String cursor);
    byte[] download(Connection c, RemoteFile f);
}
// RemoteFile: id, name, parentPath, mimeType, size, etag/version, modifiedTime
```
Hexagonal placement: a new `mod15_ingestion` (or fold into MOD-04 intake) with `domain` / `application` (poll job, mapping engine, ledger) / `adapter.external` (Drive, OneDrive) / `adapter.web` (Connections + Intake-review endpoints).

## 4. Folder layout & mapping rules

Expected layout under the connected root:
```
<root>/
  INNOVATECODE IT SRL/        ← or "49443957" (CUI)
    2026-05/
      Stat_salarii_…2026_05.pdf
      Pontaj_…2026_05.pdf
    2026-06/ …
  Lumina Verde SRL/ …
```

Resolution order (first hit wins; failure → review queue):
1. **Company** — explicit per-company folder mapping (tenant-configured) → else first-level folder name matched to a company by **name or CUI** (reuse `CompanyMatcher`).
2. **Period** — second-level folder `YYYY-MM` → else document content-date (extraction) → else file `modifiedTime` month.
3. **Type** — payroll-structured connection ⇒ `forcedType=PAYROLL`. (Generalisable later: a `Payroll/` subfolder convention, or auto-classifier for mixed folders.)
4. **Wrong-party guard** — if a document's extracted CUI contradicts the mapped company, flag → review (same guard used for trial balances).

## 5. Incremental poll

- **Drive:** `changes.list` with a saved page token (filtered to the watched subtree), or `files.list(q="'<folderId>' in parents and modifiedTime > <cursor>")` recursed over subfolders.
- **OneDrive (later):** `/drive/items/{folderId}/delta` (folder-scoped delta link).
- Cursor persisted per connection. One idempotent pgmq job per connection, exponential backoff, DLQ — reuses the existing worker/job conventions. Cadence configurable (default ~15 min).

## 6. Idempotency, dedup & provenance

`import_file` ledger row per seen file, `unique(connection_id, source_ref)`:
- Skip unchanged via `source_ref` (provider fileId) + `source_etag`/version (no download needed).
- `content_sha256` catches the same file re-appearing under a new name (also closes the long-standing "Document has no content hash" gap).
- Records provenance (connection, path, imported_at, resulting `document_id`) for audit (MOD-12) and a "imported from Drive" badge.

## 7. Data model (new)

```sql
source_connection(
  id uuid pk, tenant_id uuid not null,
  provider text not null,            -- GOOGLE_DRIVE | ONEDRIVE
  display_name text,
  root_folder_id text not null,
  config jsonb,                      -- per-company folder map, type policy, cadence
  oauth_token_enc bytea,             -- encrypted refresh token / SA ref
  cursor text, status text, last_synced_at timestamptz,
  created_at timestamptz default now()
);  -- RLS: tenant_isolation; FORCE RLS

import_file(
  id uuid pk, tenant_id uuid not null,
  connection_id uuid not null references source_connection(id),
  source_ref text not null,          -- provider file id
  source_etag text, content_sha256 text,
  document_id uuid references document(id),
  status text not null,              -- IMPORTED | NEEDS_REVIEW | REJECTED | DUPLICATE
  detail jsonb,                      -- path, resolved company/period, flags
  created_at timestamptz default now(),
  unique(connection_id, source_ref)
);  -- RLS: tenant_isolation; FORCE RLS
```

## 8. Security & PII

- **OAuth tokens encrypted at rest**, never committed; refresh handled server-side; revocable from the Connections screen.
- **Scope (Drive):** watching an existing folder needs `drive.readonly` (broad). Privacy-sensitive tenants may instead **share a dedicated folder with a service account** so the app sees only that folder — offered as an option. *Decision deferred (see Open items).*
- **Tenant isolation:** `source_connection` + `import_file` under RLS (FORCE). Connectors only ever resolve to companies within the connection's tenant.
- **Payroll is sensitive:** attachments encrypted at rest (existing storage), PII masked in logs, provenance retained for GDPR.
- **Wrong-party guard** before posting prevents cross-company misfiling even on a mis-organised folder.

## 9. Intake-review queue (staff UI)

A new "Intake" screen: auto-imported documents flow straight in (badged with source); unresolved items (unknown folder/company, ambiguous period, wrong-party flagged, unclassifiable) wait for the accountant to confirm **company / period / type** with one click. Reuses existing doc-status / wrong-party UI patterns. This is the human-in-the-loop safety valve and the visible "it's working" surface.

## 10. Phasing

1. **P1** — schema (`source_connection`, `import_file`), `CloudFolderConnector` port, **Google Drive** OAuth + Connections screen + poll job, payroll mapping (`company/[month]/files`), ledger, Intake-review queue.
2. **P2** — OneDrive adapter (Graph `/delta`).
3. **P3** — generalise to bank statements/invoices (auto-classifier, mixed folders).
4. **P4 (separate track)** — **Gmail intake** connector to a dedicated intake mailbox (sender→rep→company via `representative_link`); then push/webhooks; then on-prem agent if ever needed.

## 11. Considerations checklist (focused)

| Concern | Applies | Note |
|---|---|---|
| Integration / connectors | ✅ | New port + Drive/OneDrive adapters |
| Async / scheduling | ✅ | Existing worker + pgmq, idempotent jobs, DLQ |
| Auth / token mgmt | ✅ | Per-tenant OAuth, encrypted, revocable |
| Idempotency / dedup | ✅ | Ledger + content hash (new) |
| Multi-tenancy | ✅ | RLS on connection + ledger |
| Data protection / PII | ✅ | Encrypted tokens & docs; masked logs; wrong-party guard |
| API design | ✅ | `/connections` CRUD + OAuth callback + Intake-review |
| Observability | ✅ | Per-connection sync status + DLQ; "last synced" |
| Push/webhooks | Deferred | Poll first; webhooks as latency optimisation |
| On-prem agent / local disk | ❌ (now) | Source is cloud; revisit only for true on-prem shares |
| Multi-region / sharding / heavy perf | ❌ | Low volume (a few files/company/month) |

## 12. Open items

- **Drive scope**: broad `drive.readonly` vs dedicated shared-folder + service account — pick per privacy posture.
- **Company auto-match vs explicit**: confirm whether first-level folder auto-match (name/CUI) is enough, or always require an explicit per-company folder mapping.
- **Period source of truth** when content-date and month-folder disagree (proposed: content-date wins).
- **Re-import semantics** when a file changes (new version) after it already produced a document (replace vs new version vs ignore).
- **Cadence** default and whether tenants can configure it.
