-- Meta Cloud API configuration is separate from the existing Twilio fields.
-- The access token is encrypted application ciphertext; identifiers are not secrets.
ALTER TABLE tenant_whatsapp_provider
    ADD COLUMN meta_business_portfolio_id text,
    ADD COLUMN meta_waba_id text,
    ADD COLUMN meta_phone_number_id text,
    ADD COLUMN meta_access_token_enc text;
