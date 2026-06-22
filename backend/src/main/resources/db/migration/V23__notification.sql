-- =====================================================================
-- MOD-09 Notifications: in-app notifications for firm staff. One row per
-- recipient so each staff member marks read independently. Created when a
-- representative uploads a document (and other events later). Email delivery
-- is tracked by the per-module email tables; this table is the in-app feed.
-- =====================================================================

CREATE TABLE notification (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenant(id),
    recipient_user_id uuid NOT NULL,
    type              text NOT NULL,            -- DOCUMENT_UPLOADED, ...
    title             text NOT NULL,
    body              text NOT NULL,
    company_id        uuid,
    company_name      text,
    document_id       uuid,
    read_at           timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_recipient ON notification(tenant_id, recipient_user_id, created_at DESC);
CREATE INDEX idx_notification_unread ON notification(tenant_id, recipient_user_id) WHERE read_at IS NULL;

ALTER TABLE notification ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON notification TO myfinance_app;
    END IF;
END $$;
