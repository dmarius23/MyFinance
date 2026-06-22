-- =====================================================================
-- MOD-06 Reports: store the extracted/computed financial report per
-- company/month (from the uploaded trial balance), and keep a history of
-- report emails sent to the representative. Re-upload replaces the snapshot
-- and bumps the version. report_json holds the computed ReportData.
-- =====================================================================

CREATE TABLE trial_balance (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenant(id),
    company_id    uuid NOT NULL,
    period_month  date NOT NULL,
    document_id   uuid NOT NULL,
    version       int  NOT NULL DEFAULT 1,
    balanced      boolean NOT NULL DEFAULT false,
    report_json   text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_trial_balance_company_period ON trial_balance(tenant_id, company_id, period_month);
CREATE INDEX idx_trial_balance_period ON trial_balance(tenant_id, period_month);

CREATE TABLE report_email (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenant(id),
    company_id    uuid NOT NULL,
    period_month  date NOT NULL,
    recipient     text,
    body          text NOT NULL,
    status        text NOT NULL,               -- SENT / FAILED
    error         text,
    sent_at       timestamptz NOT NULL DEFAULT now(),
    sent_by       uuid
);
CREATE INDEX idx_report_email_company_period ON report_email(tenant_id, company_id, period_month);
CREATE INDEX idx_report_email_period ON report_email(tenant_id, period_month);

ALTER TABLE trial_balance ENABLE ROW LEVEL SECURITY;
ALTER TABLE trial_balance FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON trial_balance
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE report_email ENABLE ROW LEVEL SECURITY;
ALTER TABLE report_email FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON report_email
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON trial_balance TO myfinance_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON report_email TO myfinance_app;
    END IF;
END $$;
