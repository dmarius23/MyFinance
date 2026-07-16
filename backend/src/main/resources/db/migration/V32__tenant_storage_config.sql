-- =====================================================================
-- Per-tenant document storage strategy (see docs/MyFinance-tenant-storage-strategy-design-v1.md).
-- A tenant chooses where its document bytes live:
--   SUPABASE_ONLY  (default) — bytes in Supabase Storage only.
--   DRIVE_MIRROR            — Supabase canonical + a copy pushed to the firm's Google Shared Drive.
--   DRIVE_PRIMARY           — Google Drive canonical, Supabase as cache (Phase 2).
-- Phase 1 ships SUPABASE_ONLY + DRIVE_MIRROR. Metadata + access control (RLS) always stay in the app.
-- =====================================================================

CREATE TABLE tenant_storage_config (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid NOT NULL REFERENCES tenant(id),
    storage_mode          text NOT NULL DEFAULT 'SUPABASE_ONLY',  -- SUPABASE_ONLY | DRIVE_MIRROR | DRIVE_PRIMARY
    shared_drive_id       text,                                    -- the firm-owned Google Shared Drive id
    write_root_folder_id  text,                                    -- root folder within the shared drive
    cache_ttl_seconds     int,                                     -- mode DRIVE_PRIMARY cache policy (Phase 2)
    cache_max_bytes       bigint,
    updated_at            timestamptz NOT NULL DEFAULT now(),
    created_at            timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id)
);

ALTER TABLE tenant_storage_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_storage_config FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_storage_config
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Where each document's bytes actually live, so a mode switch never strands old files and the mirror
-- copy can be cleaned up on delete. Existing rows are all in Supabase.
ALTER TABLE document
    ADD COLUMN storage_backend text NOT NULL DEFAULT 'SUPABASE',  -- SUPABASE | DRIVE
    ADD COLUMN drive_file_id   text;                              -- the Drive copy (mirror); null otherwise

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_storage_config TO myfinance_app;
    END IF;
END $$;
