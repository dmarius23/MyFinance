-- =====================================================================
-- Consolidate the two "documents on Google Drive" configs into one: the MOD-15 source connection.
-- A Drive connection can now be write-enabled, making it BOTH the ingestion source (read/pull) and the
-- storage mirror target (write). This replaces the separate tenant_storage_config from V32.
-- The document.storage_backend / drive_file_id columns (V32) stay — they track the mirror copy.
-- =====================================================================

ALTER TABLE source_connection
    ADD COLUMN write_enabled boolean NOT NULL DEFAULT false;  -- true → mirror uploads into this Drive

DROP TABLE IF EXISTS tenant_storage_config;
