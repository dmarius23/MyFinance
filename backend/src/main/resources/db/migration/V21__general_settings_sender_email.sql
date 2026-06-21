-- =====================================================================
-- The accounting firm's outbound email address (the "From" for all client
-- emails — tax payments, document reminders, payroll). Per-tenant config.
-- The display name on the From is the logged-in user's name (not stored here).
-- =====================================================================

ALTER TABLE general_settings ADD COLUMN sender_email text;
