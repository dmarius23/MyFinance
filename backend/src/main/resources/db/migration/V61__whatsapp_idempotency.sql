-- Idempotency keys are scoped to the tenant and delivery channel. A hash lets the
-- API reject accidental reuse of a key for a different WhatsApp request.
ALTER TABLE email_history ADD COLUMN idempotency_key text;
ALTER TABLE email_history ADD COLUMN idempotency_hash text;

CREATE UNIQUE INDEX uq_email_history_tenant_channel_idempotency
    ON email_history (tenant_id, channel, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
