-- =====================================================================
-- MOD-08 Payroll: keep a full history of payroll emails sent to clients,
-- per company/month. Each send attaches the company's payroll documents
-- (pay statement, payslip, timesheet). Append-only — a resend is a new
-- row — so the Payroll list can show "last sent" and the log keeps history.
-- Payroll files themselves reuse the existing `document` table (type=PAYROLL).
-- =====================================================================

CREATE TABLE payroll_email (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenant(id),
    company_id    uuid NOT NULL,
    period_month  date NOT NULL,
    recipient     text,
    body          text NOT NULL,
    status        text NOT NULL,               -- SENT / FAILED
    error         text,
    document_ids  text NOT NULL DEFAULT '',    -- comma-separated document ids attached
    sent_at       timestamptz NOT NULL DEFAULT now(),
    sent_by       uuid
);
CREATE INDEX idx_payroll_email_company_period ON payroll_email(tenant_id, company_id, period_month);
CREATE INDEX idx_payroll_email_period ON payroll_email(tenant_id, period_month);

ALTER TABLE payroll_email ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_email FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payroll_email
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON payroll_email TO myfinance_app;
    END IF;
END $$;
