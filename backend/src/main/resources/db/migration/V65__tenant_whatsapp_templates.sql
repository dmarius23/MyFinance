CREATE TABLE tenant_whatsapp_template (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid NOT NULL REFERENCES tenant(id),
    kind                 text NOT NULL, -- TAX / PAYROLL / REPORT / DOCUMENT_REMINDER
    language             text NOT NULL, -- e.g. ro_RO / en_US
    meta_template_name   text NOT NULL,
    category             text NOT NULL DEFAULT 'UTILITY',
    status               text NOT NULL DEFAULT 'PENDING', -- PENDING / APPROVED / DISABLED
    version              text,
    variable_schema      text,
    approved_at          timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_whatsapp_template UNIQUE (tenant_id, kind, language)
);

CREATE INDEX idx_tenant_whatsapp_template_lookup
    ON tenant_whatsapp_template (tenant_id, kind, language, status);

ALTER TABLE tenant_whatsapp_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_whatsapp_template FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_whatsapp_template
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_whatsapp_template TO myfinance_app;
    END IF;
END $$;
