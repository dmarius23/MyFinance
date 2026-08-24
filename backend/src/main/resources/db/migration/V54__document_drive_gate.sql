-- Document validation gate for the Drive mirror.
-- content_sha256      : SHA-256 of the stored bytes — enables cross-document duplicate detection on upload.
-- drive_block_reason  : null = eligible to mirror; else DUPLICATE | WRONG_COMPANY | WRONG_PERIOD.
-- drive_block_detail  : human-readable reason shown in the UI.
-- decl_kind           : declaration form (D100/D112/D300) captured at upload, for Drive routing.
-- dominant_obligation_cod : the largest-amount obligation code (D100 sub-routing: 628/604/103…).
ALTER TABLE document ADD COLUMN content_sha256          text;
ALTER TABLE document ADD COLUMN drive_block_reason      text;
ALTER TABLE document ADD COLUMN drive_block_detail      text;
ALTER TABLE document ADD COLUMN decl_kind               text;
ALTER TABLE document ADD COLUMN dominant_obligation_cod text;

-- Duplicate lookup: identical bytes already stored for the same company/period/type (tenant-scoped by RLS).
CREATE INDEX idx_document_dedup
    ON document (tenant_id, company_id, period_month, type, content_sha256);
