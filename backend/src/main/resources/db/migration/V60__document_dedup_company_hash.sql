-- Global document dedup: key on (company, content hash) instead of (company, period, type, hash).
--
-- Why the key shrinks. Identical bytes ARE the same document. Period and type are not properties of the
-- file, they are values each ingestion path INFERS, and the paths infer them differently: a manual upload
-- takes the month the user picked, while a Drive import derives it from filename -> path -> document
-- content -> modified date, and the type comes from adaptive classification. So the same PDF arriving
-- manually and from Drive could land under a different month or type, produce a different dedup key, and
-- be stored twice with neither copy flagged.
--
-- The partial predicate matters: rows uploaded before V54 have no hash, and NULL = NULL is never true in
-- SQL, so they can neither match each other nor be matched. Excluding them keeps the index small and makes
-- that intent explicit.
CREATE INDEX IF NOT EXISTS idx_document_dedup_company_hash
    ON document (tenant_id, company_id, content_sha256)
    WHERE content_sha256 IS NOT NULL;

-- Superseded: period_month and type sit between company_id and content_sha256, so this index cannot serve
-- a lookup on (tenant_id, company_id, content_sha256).
DROP INDEX IF EXISTS idx_document_dedup;
