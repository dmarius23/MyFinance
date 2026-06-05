CREATE TABLE document (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenant(id),
    company_id        uuid NOT NULL REFERENCES company(id),
    period_month      date NOT NULL,
    type              text NOT NULL,
    source            text NOT NULL,
    status            text NOT NULL,
    original_filename text NOT NULL,
    content_type      text NOT NULL,
    size_bytes        bigint NOT NULL,
    storage_key       text NOT NULL,
    uploaded_by       uuid,
    uploaded_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_document_company_period ON document(tenant_id, company_id, period_month);

ALTER TABLE document ENABLE ROW LEVEL SECURITY;
ALTER TABLE document FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON document TO myfinance_app;
    END IF;
END $$;
