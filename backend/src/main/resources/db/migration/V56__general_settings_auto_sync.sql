-- Per-tenant automatic Drive sync schedule. Defaults reproduce the previous global behaviour
-- (enabled, 02:00 Europe/Bucharest) so no tenant loses auto-sync on upgrade.
ALTER TABLE general_settings ADD COLUMN auto_sync_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE general_settings ADD COLUMN auto_sync_hour    integer NOT NULL DEFAULT 2;

ALTER TABLE general_settings ADD CONSTRAINT general_settings_auto_sync_hour_range
    CHECK (auto_sync_hour BETWEEN 0 AND 23);
