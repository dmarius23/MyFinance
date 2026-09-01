-- Per-tenant messaging provider config: each accounting firm sets its own email (SMTP) and WhatsApp
-- credentials, used when THAT firm sends client emails / messages. Secrets (SMTP password, WhatsApp auth
-- token) are AES-256-GCM encrypted by the app (SecretCipher) — the database only ever holds ciphertext.

CREATE TABLE tenant_email_provider (
    tenant_id         uuid PRIMARY KEY REFERENCES tenant(id),
    enabled           boolean NOT NULL DEFAULT false,
    from_email        text,
    from_name         text,
    smtp_host         text,
    smtp_port         integer,
    smtp_username     text,
    smtp_password_enc text,        -- AES-GCM ciphertext (base64), app-encrypted; never plaintext
    updated_at        timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE tenant_email_provider ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_email_provider FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_email_provider
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE tenant_whatsapp_provider (
    tenant_id      uuid PRIMARY KEY REFERENCES tenant(id),
    mode           text NOT NULL DEFAULT 'OFF',   -- OFF | TWILIO | CLICK_TO_CHAT
    account_sid    text,
    auth_token_enc text,           -- AES-GCM ciphertext (base64), app-encrypted; never plaintext
    from_number    text,
    updated_at     timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE tenant_whatsapp_provider ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_whatsapp_provider FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_whatsapp_provider
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_email_provider TO myfinance_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_whatsapp_provider TO myfinance_app;
    END IF;
END $$;
