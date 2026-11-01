ALTER TABLE email_history ADD COLUMN provider_message_id text;
ALTER TABLE email_history ADD COLUMN provider_status text;
ALTER TABLE email_history ADD COLUMN provider_status_at timestamptz;

CREATE UNIQUE INDEX uq_email_history_whatsapp_provider_message
    ON email_history (provider_message_id)
    WHERE channel = 'WHATSAPP' AND provider_message_id IS NOT NULL;

CREATE INDEX idx_email_history_whatsapp_provider_message
    ON email_history (tenant_id, provider_message_id)
    WHERE channel = 'WHATSAPP';
