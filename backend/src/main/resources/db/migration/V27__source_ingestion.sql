-- MOD-15: ingest documents from a tenant cloud folder (Google Drive first) instead of manual upload.
-- The admin configures the connection (which folder); no secrets are stored here — the app authenticates
-- with one service account whose key lives in env, and the admin shares the folder with it.

CREATE TABLE source_connection (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenant(id),
    provider        text NOT NULL,                 -- GOOGLE_DRIVE | ONEDRIVE | FAKE
    display_name    text NOT NULL,
    root_folder_id  text NOT NULL,                 -- the folder the app watches
    forced_type     text,                          -- e.g. PAYROLL for a payroll-only folder; null = auto-classify
    config          jsonb NOT NULL DEFAULT '{}'::jsonb,  -- optional explicit company→subfolder map, cadence
    cursor          text,                          -- incremental poll cursor (provider page/delta token)
    status          text NOT NULL DEFAULT 'ACTIVE',
    last_synced_at  timestamptz,
    last_result     text,                          -- short human summary of the last sync
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_source_connection_tenant ON source_connection(tenant_id);

ALTER TABLE source_connection ENABLE ROW LEVEL SECURITY;
ALTER TABLE source_connection FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON source_connection
    USING (tenant_id = (NULLIF(current_setting('app.tenant_id', true), ''))::uuid);

-- Per-file import ledger: idempotency (don't re-import) + provenance + the review queue.
CREATE TABLE import_file (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenant(id),
    connection_id   uuid NOT NULL REFERENCES source_connection(id) ON DELETE CASCADE,
    source_ref      text NOT NULL,                 -- provider file id
    source_etag     text,                          -- provider version/etag/modifiedTime
    content_sha256  text,
    filename        text,
    source_path     text,                          -- folder path the file was found under
    document_id     uuid REFERENCES document(id),  -- set when imported
    status          text NOT NULL,                 -- IMPORTED | NEEDS_REVIEW | REJECTED | DUPLICATE
    detail          text,                          -- why it needs review / was rejected
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_import_file_ref UNIQUE (connection_id, source_ref)
);
CREATE INDEX idx_import_file_tenant ON import_file(tenant_id);
CREATE INDEX idx_import_file_status ON import_file(connection_id, status);

ALTER TABLE import_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_file FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON import_file
    USING (tenant_id = (NULLIF(current_setting('app.tenant_id', true), ''))::uuid);
