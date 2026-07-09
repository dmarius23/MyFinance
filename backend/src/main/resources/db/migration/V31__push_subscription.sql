-- =====================================================================
-- MOD-09 Notifications: Web Push (VAPID) subscriptions for the PWA. One row
-- per (user, browser endpoint) so a representative can register several devices;
-- delivery pushes to every row for the recipient. The endpoint is the browser's
-- push service URL; p256dh + auth are the client's public encryption keys. Rows
-- are pruned when the push service reports the subscription gone (404/410).
-- Tenant-scoped via RLS like every other table.
-- =====================================================================

CREATE TABLE push_subscription (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    user_id     uuid NOT NULL,
    endpoint    text NOT NULL,
    p256dh      text NOT NULL,
    auth        text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, endpoint)
);
CREATE INDEX idx_push_subscription_user ON push_subscription(tenant_id, user_id);

ALTER TABLE push_subscription ENABLE ROW LEVEL SECURITY;
ALTER TABLE push_subscription FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON push_subscription
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON push_subscription TO myfinance_app;
    END IF;
END $$;
