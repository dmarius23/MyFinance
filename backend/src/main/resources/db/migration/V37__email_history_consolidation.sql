-- =====================================================================
-- S9: collapse the four near-identical email-history tables (tax_email,
-- report_email, payroll_email, document_reminder) into ONE shared
-- email_history table, partitioned by a `kind` discriminator. Same
-- columns everywhere; `related_ids` carries the tax declaration ids (TAX)
-- or attached document ids (PAYROLL), empty for the rest. Append-only.
-- Rows are copied over (preserving id / sent_at / sent_by) before the old
-- tables are dropped, so no history is lost.
-- =====================================================================

CREATE TABLE email_history (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenant(id),
    kind          text NOT NULL,                -- TAX / REPORT / PAYROLL / DOCUMENT_REMINDER
    company_id    uuid NOT NULL,
    period_month  date NOT NULL,
    recipient     text,
    body          text NOT NULL,
    status        text NOT NULL,                -- SENT / FAILED
    error         text,
    related_ids   text NOT NULL DEFAULT '',     -- comma-separated ids this send covered/attached
    sent_at       timestamptz NOT NULL DEFAULT now(),
    sent_by       uuid
);
CREATE INDEX idx_email_history_kind_company_period ON email_history(tenant_id, kind, company_id, period_month);
CREATE INDEX idx_email_history_kind_period ON email_history(tenant_id, kind, period_month);

ALTER TABLE email_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON email_history
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON email_history TO myfinance_app;
    END IF;
END $$;

-- ---- Copy existing history over (kind-tagged; map the per-type id columns to related_ids) ----
INSERT INTO email_history (id, tenant_id, kind, company_id, period_month, recipient, body, status,
                           error, related_ids, sent_at, sent_by)
SELECT id, tenant_id, 'TAX', company_id, period_month, recipient, body, status, error,
       declaration_ids, sent_at, sent_by
FROM tax_email;

INSERT INTO email_history (id, tenant_id, kind, company_id, period_month, recipient, body, status,
                           error, related_ids, sent_at, sent_by)
SELECT id, tenant_id, 'PAYROLL', company_id, period_month, recipient, body, status, error,
       document_ids, sent_at, sent_by
FROM payroll_email;

INSERT INTO email_history (id, tenant_id, kind, company_id, period_month, recipient, body, status,
                           error, related_ids, sent_at, sent_by)
SELECT id, tenant_id, 'REPORT', company_id, period_month, recipient, body, status, error,
       '', sent_at, sent_by
FROM report_email;

INSERT INTO email_history (id, tenant_id, kind, company_id, period_month, recipient, body, status,
                           error, related_ids, sent_at, sent_by)
SELECT id, tenant_id, 'DOCUMENT_REMINDER', company_id, period_month, recipient, body, status, error,
       '', sent_at, sent_by
FROM document_reminder;

DROP TABLE tax_email;
DROP TABLE payroll_email;
DROP TABLE report_email;
DROP TABLE document_reminder;
