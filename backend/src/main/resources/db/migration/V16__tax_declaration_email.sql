-- =====================================================================
-- MOD-07 persistence: store each uploaded ANAF declaration's extracted
-- summary, and keep a full history of state-payment emails sent — per
-- company/month and per declaration. Emails can be resent any time; every
-- send is a new row, so the history is append-only.
-- =====================================================================

CREATE TABLE tax_declaration (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    company_id     uuid NOT NULL,
    period_month   date NOT NULL,
    document_id    uuid NOT NULL,
    type           text NOT NULL,                 -- D100 / D112 / D300
    cui            text,
    declared_total numeric(14,2),
    computed_total numeric(14,2) NOT NULL DEFAULT 0,
    mismatch       boolean NOT NULL DEFAULT false,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_tax_declaration_document ON tax_declaration(tenant_id, document_id);
CREATE INDEX idx_tax_declaration_company_period ON tax_declaration(tenant_id, company_id, period_month);

CREATE TABLE tax_email (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenant(id),
    company_id      uuid NOT NULL,
    period_month    date NOT NULL,
    recipient       text,
    body            text NOT NULL,
    status          text NOT NULL,                -- SENT / FAILED
    error           text,
    declaration_ids text NOT NULL DEFAULT '',     -- comma-separated tax_declaration ids this email covered
    sent_at         timestamptz NOT NULL DEFAULT now(),
    sent_by         uuid
);
CREATE INDEX idx_tax_email_company_period ON tax_email(tenant_id, company_id, period_month);

ALTER TABLE tax_declaration ENABLE ROW LEVEL SECURITY;
ALTER TABLE tax_declaration FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tax_declaration
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE tax_email ENABLE ROW LEVEL SECURITY;
ALTER TABLE tax_email FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tax_email
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON tax_declaration TO myfinance_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON tax_email TO myfinance_app;
    END IF;
END $$;
