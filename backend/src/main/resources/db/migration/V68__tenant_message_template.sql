-- Per-tenant message wording. A row exists ONLY for a kind the tenant customized; absent rows fall back
-- to the built-in Java defaults (DefaultMessageTemplates), so defaults stay centrally improvable and
-- "reset to default" is just a DELETE. Bodies are prose with {{placeholders}}; the computed lists
-- (payment lines, missing transactions) are rendered by the app, never by the tenant.

CREATE TABLE tenant_message_template (
    tenant_id  uuid NOT NULL REFERENCES tenant(id),
    kind       text NOT NULL,
    subject    text,
    body       text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid,
    PRIMARY KEY (tenant_id, kind)
);
ALTER TABLE tenant_message_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_message_template FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_message_template
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_message_template TO myfinance_app;
    END IF;
END $$;
