-- =====================================================================
-- Scope the ingestion content-hash dedupe to (connection, company, period): the same bytes legitimately
-- appear in different months (e.g. an identical document filed for two periods) and must import as
-- separate documents rather than being skipped as a global duplicate. Backfill is unnecessary — existing
-- rows keep NULL company/period (they only affect future dedupe checks for the same file).
-- =====================================================================

ALTER TABLE import_file
    ADD COLUMN company_id   uuid,
    ADD COLUMN period_month date;

CREATE INDEX idx_import_file_dedupe
    ON import_file(connection_id, company_id, period_month, content_sha256);
