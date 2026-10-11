-- Gmail email-agent foundation. OAuth tokens are encrypted application data and must never be logged.
CREATE TABLE email_connection (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenant(id),
    display_name        text NOT NULL,
    mailbox_address     text NOT NULL,
    refresh_token_enc   text,
    oauth_state_hash     text,
    oauth_state_expires_at timestamptz,
    gmail_history_id    text,
    watch_expires_at    timestamptz,
    status              text NOT NULL DEFAULT 'ACTIVE',
    last_synced_at      timestamptz,
    last_result         text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_email_connection_mailbox UNIQUE (tenant_id, mailbox_address)
);
CREATE INDEX idx_email_connection_tenant ON email_connection(tenant_id);

ALTER TABLE email_connection ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_connection FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON email_connection
    USING (tenant_id = (NULLIF(current_setting('app.tenant_id', true), ''))::uuid)
    WITH CHECK (tenant_id = (NULLIF(current_setting('app.tenant_id', true), ''))::uuid);

CREATE TABLE email_message (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenant(id),
    connection_id       uuid NOT NULL REFERENCES email_connection(id) ON DELETE CASCADE,
    gmail_message_id     text NOT NULL,
    thread_id            text,
    sender_email         text NOT NULL,
    subject              text,
    received_at          timestamptz,
    status               text NOT NULL,
    detail               text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_email_message_ref UNIQUE (connection_id, gmail_message_id)
);
CREATE INDEX idx_email_message_tenant ON email_message(tenant_id);
CREATE INDEX idx_email_message_status ON email_message(connection_id, status);

ALTER TABLE email_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_message FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON email_message
    USING (tenant_id = (NULLIF(current_setting('app.tenant_id', true), ''))::uuid)
    WITH CHECK (tenant_id = (NULLIF(current_setting('app.tenant_id', true), ''))::uuid);

CREATE TABLE email_attachment (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenant(id),
    message_id          uuid NOT NULL REFERENCES email_message(id) ON DELETE CASCADE,
    gmail_attachment_id  text NOT NULL,
    filename             text NOT NULL,
    content_type        text,
    content_sha256      text,
    document_id         uuid REFERENCES document(id),
    status              text NOT NULL,
    detail              text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_email_attachment_ref UNIQUE (message_id, gmail_attachment_id)
);
CREATE INDEX idx_email_attachment_tenant ON email_attachment(tenant_id);

ALTER TABLE email_attachment ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_attachment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON email_attachment
    USING (tenant_id = (NULLIF(current_setting('app.tenant_id', true), ''))::uuid)
    WITH CHECK (tenant_id = (NULLIF(current_setting('app.tenant_id', true), ''))::uuid);
