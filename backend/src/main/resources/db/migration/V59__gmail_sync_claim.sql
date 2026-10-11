-- Per-connection claim prevents duplicate Pub/Sub deliveries from processing one Gmail cursor concurrently.
CREATE TABLE email_sync_claim (
    connection_id uuid PRIMARY KEY REFERENCES email_connection(id) ON DELETE CASCADE,
    tenant_id     uuid NOT NULL REFERENCES tenant(id),
    claimed_at    timestamptz NOT NULL DEFAULT now(),
    lock_until    timestamptz NOT NULL
);

CREATE INDEX idx_email_sync_claim_tenant ON email_sync_claim(tenant_id);

ALTER TABLE email_sync_claim ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_sync_claim FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON email_sync_claim
    USING (tenant_id = (NULLIF(current_setting('app.tenant_id', true), ''))::uuid)
    WITH CHECK (tenant_id = (NULLIF(current_setting('app.tenant_id', true), ''))::uuid);
