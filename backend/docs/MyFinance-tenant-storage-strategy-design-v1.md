# MyFinance — Per-tenant document storage strategy (Supabase / Google Drive) — design note v1

**Status:** Draft for review · **Scope:** where document *bytes* live, per tenant · **Owner:** TBD
**Related:** extends [`MyFinance-folder-ingestion-design-v1.md`](MyFinance-folder-ingestion-design-v1.md) (MOD-15, read-only Drive *ingestion*). This note is about Drive as a *store*, not a source.

## 1. Goal

Let each accounting firm (tenant) choose **where its clients' document bytes live**, without changing the rest of the app:

- **`SUPABASE_ONLY`** — today's behaviour. Bytes in Supabase Storage; nothing leaves the app boundary. *(Default; existing tenants unaffected.)*
- **`DRIVE_MIRROR`** (option B) — Supabase stays the canonical store; every document is also **copied into the firm's Google Drive** in a browsable folder tree.
- **`DRIVE_PRIMARY`** (option A) — the firm's **Google Drive is the canonical home**; Supabase is used only as a fast, evictable cache.

The firm configures this per tenant. The upload button, previews, extraction and reconciliation stay identical — they keep calling the `DocumentStorage` port.

Non-goals (v1): OneDrive/S3/Dropbox as stores (the port generalises, implement later); real-time two-way editing of the same file in Drive and the app; moving *metadata* out of Postgres (metadata + RLS always stay in the app).

## 2. Key decisions

| Decision | Choice | Rationale |
|---|---|---|
| Granularity | **Per-tenant** storage mode | Firms opt in at their own pace; multi-tenant SaaS fit |
| Modes | `SUPABASE_ONLY` \| `DRIVE_MIRROR` \| `DRIVE_PRIMARY` | The three shapes we discussed, selectable |
| Write path | **One path: Supabase first, worker pushes to Drive**; mode = *retention/canonical policy* | Upload never blocks on Drive; A & B share machinery; reads always work |
| Drive ownership | **Shared Drive the firm owns**, our **service account added as a member** | Files owned by the firm (no SA-quota/ownership mess); trivial offboarding; stays service-account auth |
| Drive scope | `https://www.googleapis.com/auth/drive` (write) **scoped to the Shared Drive** | Ingestion keeps `drive.readonly`; storage needs write |
| Folder layout (write) | Reuse ingestion's `root / <company> / [YYYY-MM] / <type>` via `FolderMapper` + "ensure-path" | One layout for read (ingest) and write (store) |
| Abstraction | New `TenantRoutingDocumentStorage implements DocumentStorage` | Nothing upstream of the port changes |
| Source of truth | Postgres always owns **metadata + access control (RLS)**; only *bytes* relocate | Drive is outside the RLS boundary — keep the trust boundary in the app |

## 3. Core idea — one write path, mode = retention policy

Instead of three storage backends with three read/write paths, keep **one** path and let the mode decide retention:

1. **Every upload lands in Supabase first** (fast, atomic, inside the RLS trust boundary).
2. **A worker job pushes it to Drive** (outbox written in the upload transaction, relayed by the worker — existing convention; backoff + DLQ).
3. **Reads always try Supabase; on miss, fetch from Drive and repopulate.**

| Mode | Push to Drive? | Supabase copy | Canonical | Reads |
|---|---|---|---|---|
| `SUPABASE_ONLY` | no | permanent | Supabase | Supabase |
| `DRIVE_MIRROR` (B) | yes | permanent | Supabase | Supabase |
| `DRIVE_PRIMARY` (A) | yes | **cache (evictable, TTL)** | Drive | Supabase cache → miss → Drive |

This collapses A and B into the same code; A is just "the Supabase copy is an evictable cache and Drive is canonical." Extraction/reconciliation keep calling `retrieve` and never learn where the bytes are.

## 4. Architecture

```
DocumentService.upload(...)  ── the single intake chokepoint (unchanged) ──────────┐
        │  store(key, bytes, contentType)                                          │
        ▼                                                                          │
TenantRoutingDocumentStorage  (implements DocumentStorage; reads tenant mode)      │
        │                                                                          │
        ├─ write bytes → SupabaseDocumentStorage           (always; staging/canonical)
        └─ if mode ≠ SUPABASE_ONLY → enqueue DriveBlobPush(document_id)  ── outbox → worker
                                                                                   │
Worker: DriveBlobPush ── GoogleDriveDocumentStorage.put(company/[YYYY-MM]/type/…)  ┘
        │  create/resolve folder via FolderMapper + ensure-path; files.create
        ▼
        finalize blob location on the document:
          mode B → record drive_file_id (canonical stays Supabase)
          mode A → flip backend=DRIVE, storage_key=<driveId>; mark Supabase copy = cache

Reads:  retrieve(key) → backend=SUPABASE ? Supabase
                      : backend=DRIVE   ? Supabase cache → miss → Drive (files.get media) → repopulate
```

**Port (unchanged):**
```java
interface DocumentStorage {                 // ro.myfinance.intake.application
    StoredObject store(String key, byte[] bytes, String contentType);
    byte[] retrieve(String key);
    void delete(String key);
}
```
**Adapters:** `SupabaseDocumentStorage` (exists), `LocalFsDocumentStorage` (dev, exists), **`GoogleDriveDocumentStorage`** (new — `files.create`/`files.get?alt=media`/`files.delete` on the Shared Drive), and **`TenantRoutingDocumentStorage`** (new — selects/orchestrates per tenant). Hexagonal placement: adapters in `intake/adapter/external`; the Drive-folder resolution helper reuses `ingestion` (`FolderMapper`) + a new **ensure-path** method on the Drive connector.

## 5. Storage key & schema changes

`Document.storageKey` today is one opaque string. Extend so a location is self-describing and a mode switch never strands old files:

- **`document.storage_backend`** `text` — `SUPABASE | DRIVE` (the *canonical* backend for this doc).
- **`document.storage_key`** (existing) — canonical location *within* that backend (Supabase object path **or** Drive file id).
- **`document.drive_file_id`** `text null` — the Drive **copy** when canonical is Supabase (mode B). Unused in mode A (there `storage_key` *is* the Drive id).
- **Mode-A cache** is keyed deterministically (`cache/<company_id>/<document_id>`), TTL-evictable, **not** tracked in the DB — a miss simply refetches from Drive.

Backfill: one-time set `storage_backend='SUPABASE'` for every existing row (their `storage_key` is already the Supabase path). *(Alternative if we want many locations per doc later: a `document_blob(document_id, backend, ref, role∈{CANONICAL,MIRROR,CACHE}, sha256)` table — deferred; the 3-column form covers all three modes.)*

## 6. Google Drive write adapter

- **Target:** the firm's **Shared Drive** (`driveId`) + a configured root folder id; our service account is a **member** with content-manager rights. All `files.*` calls pass `supportsAllDrives=true` and `driveId`.
- **Folder resolution (write):** reuse `FolderMapper`'s layout `…/<company name or CUI>/<YYYY-MM>/<type>`; add `ensurePath(connection, segments[]) → folderId` (look up each segment under its parent, `files.create(mimeType=application/vnd.google-apps.folder)` if missing). Cache resolved folder ids per (tenant, path) to avoid repeat lookups.
- **Put:** `files.create` (multipart: metadata {name, parents:[folderId]} + media bytes) → returns the Drive file id, stored as above.
- **Get / delete:** `files.get?alt=media` / `files.delete` by id.
- **Provenance loop-guard:** a file the *store* path writes to Drive must **not** be re-ingested by MOD-15 as a "new" document (infinite loop). Tag written files with an `appProperties.myfinanceDocId=<id>` and have the ingestion mapper skip files carrying our own doc id (or write under a folder the ingestion connection doesn't watch).

## 7. Auth & ownership (decided)

- **Model:** Shared Drive owned by the firm; our service account added as a **member** (content manager). This is the decided answer — files are owned by the firm, no personal-quota/ownership issues, offboarding = remove the service account, and it **extends** the current service-account auth rather than adding per-user OAuth.
- **Scope split:** ingestion (read) keeps `drive.readonly`; storage (write) uses `drive`. The service-account key stays in env/secret store (never committed), same as today.
- **Config carrier:** extend the existing per-tenant `source_connection` (or a sibling `tenant_storage_config`) with `storage_mode`, `shared_drive_id`, `write_root_folder_id`. RLS `FORCE`, tenant-isolation policy, mandatory cross-tenant test (golden rule #1).

## 8. Reads, caching & eviction (mode A)

- Read tries the Supabase cache first (deterministic key), miss → Drive `get` → write-through to cache.
- Cache eviction: TTL or LRU/size cap per tenant (config). Hot paths (a just-uploaded month under reconciliation) stay warm; cold history evicts and refetches on demand.
- Rate limits: Drive is ~queries/user/100s and 750 GB/day upload — fine for our volume (a few files/company/month), but the cache keeps extraction re-runs off Drive.

## 9. Mode transitions & backfill

- **Switching `SUPABASE_ONLY → DRIVE_*`:** applies to **new** documents immediately; existing files stay in Supabase (their backend-tagged key keeps working). Offer an **optional one-time backfill job** that walks existing documents and pushes them to Drive (mirror) or migrates+caches them (primary), reusing the same `DriveBlobPush` job.
- **Switching `DRIVE_* → SUPABASE_ONLY`:** stop pushing; for docs currently canonical on Drive (mode A), a **pull-back job** copies bytes to Supabase and flips them to `backend=SUPABASE` before the Drive link is abandoned.
- **`DRIVE_MIRROR ↔ DRIVE_PRIMARY`:** only changes cache-vs-permanent retention of the Supabase copy and which side is canonical; a light reconcile job flips the flags.

## 10. Drift & consistency (mode A only)

Humans will rename/move/delete inside the Shared Drive. For `DRIVE_PRIMARY`:
- **Deletes/moves:** a scheduled **reconcile** (reuse ingestion's poll cadence) checks canonical Drive ids still resolve; a vanished id → flag the document `blob_missing` (staff surfaced), don't hard-delete metadata.
- **Renames:** id is stable across renames, so a rename is harmless (we address by id, not name).
- **New files dropped directly in Drive:** that's the *ingestion* path (MOD-15), not storage — unchanged.
`DRIVE_MIRROR` is drift-tolerant by construction (Supabase is canonical; a mangled mirror is cosmetic and re-pushable).

## 11. Security, PII & tenant isolation

- **Trust boundary stays in the app:** Postgres owns metadata + RLS; **access control to bytes on Drive is Google's sharing model**, so the Shared Drive must be shared only with the firm + our service account. A mis-share is a data-exposure the app can't prevent → the setup flow must make the Shared-Drive scoping explicit and verify membership before enabling a Drive mode.
- **Payroll/PII** may now live in the firm's Drive (often *why* they want this) — document the data-processing boundary; keep PII masked in logs; provenance retained for GDPR (MOD-12).
- **Config & keys:** `storage_mode`/drive ids under RLS `FORCE`; service-account key via secret store; **cross-tenant isolation test** for the new config table and for `retrieve`/`delete` (tenant B must never read tenant A's bytes or Drive refs).
- **Loop-guard** (§6) prevents self-ingestion of app-written files.

## 12. Data model (new / changed)

```sql
-- Per-tenant storage policy + Drive write target (or fold into source_connection).
tenant_storage_config(
  id uuid pk, tenant_id uuid not null,
  storage_mode text not null default 'SUPABASE_ONLY',   -- SUPABASE_ONLY | DRIVE_MIRROR | DRIVE_PRIMARY
  shared_drive_id text, write_root_folder_id text,       -- required when mode != SUPABASE_ONLY
  cache_ttl_seconds int, cache_max_bytes bigint,          -- mode A cache policy (nullable → defaults)
  created_at timestamptz default now(),
  unique(tenant_id)
);  -- RLS: tenant_isolation; ENABLE + FORCE

alter table document
  add column storage_backend text not null default 'SUPABASE',  -- SUPABASE | DRIVE
  add column drive_file_id  text;                                -- Drive copy (mode B); null otherwise
-- backfill: existing rows already SUPABASE with a valid storage_key
```
Outbox job: `DriveBlobPush(document_id)` — idempotent (keyed by document id; skip if the doc already has the expected Drive location), backoff + DLQ.

## 13. Failure modes & idempotency

| Failure | Behaviour |
|---|---|
| Drive push fails (mode B) | Doc fully usable from Supabase; job retries; DLQ after N; staff badge "mirror pending" |
| Drive push fails (mode A) | Doc served from the Supabase staging copy (still `backend=SUPABASE`) until push succeeds; never lost |
| Read miss + Drive down (mode A) | Surface a transient error; Supabase cache covers hot data; retry |
| Duplicate push | `DriveBlobPush` idempotent by doc id + `appProperties` check → no duplicate Drive files |
| Tenant flips mode mid-flight | New docs follow new mode; in-flight jobs finalize under the mode captured at enqueue |

## 14. Phasing

1. **P1 — foundation + mirror (B):** `tenant_storage_config`, `document.storage_backend/drive_file_id`, `TenantRoutingDocumentStorage`, `GoogleDriveDocumentStorage` (write to Shared Drive) + `ensurePath`, `DriveBlobPush` worker job, Storage settings screen (mode + Shared-Drive picker + membership check). Proves the whole path with Supabase still canonical (lowest risk).
2. **P2 — primary (A):** Supabase-as-cache + read fallback + eviction; async "flip to Drive canonical"; drift reconcile.
3. **P3 — transitions:** backfill (SUPABASE→DRIVE) and pull-back (DRIVE→SUPABASE) jobs; mirror↔primary flip.
4. **P4 — generalise:** OneDrive/S3 write adapters behind the same port; optional `document_blob` multi-location table if needed.

## 15. Considerations checklist (focused)

| Concern | Applies | Note |
|---|---|---|
| Storage abstraction | ✅ | Existing `DocumentStorage` port; routing adapter, nothing upstream changes |
| Async / scheduling | ✅ | Outbox → worker for the Drive push; drift reconcile reuses ingestion poll |
| Auth / token mgmt | ✅ | Service account **member of the firm's Shared Drive**; `drive` write scope; key in secret store |
| Idempotency / dedup | ✅ | `DriveBlobPush` keyed by doc id + `appProperties` guard |
| Multi-tenancy | ✅ | Per-tenant mode + config under RLS FORCE; cross-tenant test mandatory |
| Data protection / PII | ✅ | Bytes may leave to firm's Drive by their choice; boundary documented; masked logs |
| Reliability / latency | ✅ | Supabase-first write; cache-backed reads; limits comfortably within Drive quotas |
| Consistency / drift | ✅ (A) | Reconcile by stable file id; `blob_missing` flag, no silent metadata loss |
| Loop with ingestion | ✅ | `appProperties.myfinanceDocId` guard so app-written files aren't re-ingested |
| Observability | ✅ | Per-doc blob status (pending/mirrored/canonical/missing); DLQ |
| Multi-region / sharding | ❌ | Low volume; out of scope |

## 16. Open items

- **Config home:** extend `source_connection` vs. a dedicated `tenant_storage_config` (leaning dedicated — storage policy is distinct from ingestion connections, and a tenant may store to Drive without ingesting from it).
- **Backfill on switch:** on/off by default? (Proposed: off; explicit "migrate existing files" action.)
- **Cache policy defaults** for mode A (TTL vs size cap) and whether tenants tune them.
- **Preview path:** rep PWA / staff previews currently stream from `DocumentStorage`; confirm the Drive `get` latency is acceptable for interactive preview or always keep a hot cache for the current + previous month.
- **Per-company override:** v1 is per-tenant; do any firms need per-*company* storage targets (different clients, different Drives)? (Deferred; the config table can gain a nullable `company_id` later.)
</content>
