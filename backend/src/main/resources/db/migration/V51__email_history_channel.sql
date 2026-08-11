-- Make the message history channel-aware so the same table backs both email and WhatsApp sends. Existing
-- rows are all email. Per-channel views filter on this column (e.g. "last email" vs "last WhatsApp").
ALTER TABLE email_history ADD COLUMN channel text NOT NULL DEFAULT 'EMAIL';
CREATE INDEX idx_email_history_channel_kind_period
    ON email_history (tenant_id, channel, kind, period_month);
