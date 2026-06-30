-- The connection config is an opaque JSON string set/read by the app (not queried as jsonb), and the
-- JPA String mapping binds it as varchar — which Postgres won't implicitly cast to jsonb (insert fails).
-- Store it as plain text.
ALTER TABLE source_connection ALTER COLUMN config DROP DEFAULT;
ALTER TABLE source_connection ALTER COLUMN config TYPE text USING config::text;
ALTER TABLE source_connection ALTER COLUMN config SET DEFAULT '{}';
