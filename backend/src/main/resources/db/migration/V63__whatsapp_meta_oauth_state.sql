CREATE TABLE whatsapp_meta_oauth_state (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    user_id      uuid NOT NULL,
    state_hash   text NOT NULL UNIQUE,
    expires_at   timestamptz NOT NULL,
    consumed_at  timestamptz
);

CREATE INDEX idx_whatsapp_meta_oauth_state_tenant
    ON whatsapp_meta_oauth_state (tenant_id, expires_at);

ALTER TABLE whatsapp_meta_oauth_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE whatsapp_meta_oauth_state FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON whatsapp_meta_oauth_state
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON whatsapp_meta_oauth_state TO myfinance_app;
    END IF;
END $$;
