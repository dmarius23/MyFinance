CREATE TABLE whatsapp_message (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              uuid NOT NULL REFERENCES tenant(id),
    kind                   text NOT NULL,
    company_id             uuid NOT NULL,
    period_month           date NOT NULL,
    recipient              text NOT NULL,
    body                   text NOT NULL,
    status                 text NOT NULL,
    error                  text,
    related_ids            text NOT NULL DEFAULT '',
    attachment_document_ids text NOT NULL DEFAULT '',
    template_name          text,
    template_language      text,
    template_variables     text,
    sent_at                timestamptz NOT NULL DEFAULT now(),
    sent_by                uuid,
    idempotency_key        text NOT NULL,
    idempotency_hash       text NOT NULL,
    provider_message_id    text,
    provider_status        text,
    provider_status_at     timestamptz
);

CREATE UNIQUE INDEX uq_whatsapp_message_tenant_idempotency
    ON whatsapp_message (tenant_id, idempotency_key);
CREATE UNIQUE INDEX uq_whatsapp_message_provider_id
    ON whatsapp_message (provider_message_id)
    WHERE provider_message_id IS NOT NULL;
CREATE INDEX idx_whatsapp_message_company_period
    ON whatsapp_message (tenant_id, company_id, kind, period_month, sent_at DESC);

ALTER TABLE whatsapp_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE whatsapp_message FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON whatsapp_message
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON whatsapp_message TO myfinance_app;
    END IF;
END $$;

INSERT INTO whatsapp_message (
    id, tenant_id, kind, company_id, period_month, recipient, body, status, error,
    related_ids, sent_at, sent_by, idempotency_key, idempotency_hash,
    provider_message_id, provider_status, provider_status_at)
SELECT id, tenant_id, kind, company_id, period_month, recipient, body, status, error,
       related_ids, sent_at, sent_by,
       COALESCE(idempotency_key, 'legacy-' || id::text),
       COALESCE(idempotency_hash, md5(id::text)),
       provider_message_id, provider_status, provider_status_at
FROM email_history
WHERE channel = 'WHATSAPP';

DELETE FROM email_history WHERE channel = 'WHATSAPP';
