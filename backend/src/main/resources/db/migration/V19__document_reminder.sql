-- =====================================================================
-- MOD-04/09: persist missing-document reminder emails sent to clients for
-- the bank-statements & invoices hub. Mirrors tax_email: one row per send
-- (append-only), so the Statements list can show "last sent" per company
-- and the notification log keeps full history. Reminders can be resent any
-- time; every send is a new row.
-- =====================================================================

CREATE TABLE document_reminder (
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
CREATE INDEX idx_document_reminder_company_period ON document_reminder(tenant_id, company_id, period_month);
CREATE INDEX idx_document_reminder_period ON document_reminder(tenant_id, period_month);

ALTER TABLE document_reminder ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_reminder FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document_reminder
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON document_reminder TO myfinance_app;
    END IF;
END $$;
