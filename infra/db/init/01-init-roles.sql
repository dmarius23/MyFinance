-- Runs once on first container init (before the app/Flyway connect).
-- Creates the RLS-subject application role. It is intentionally NOT a superuser and has no
-- BYPASSRLS, so PostgreSQL row-level security actually constrains it. Flyway connects as
-- 'postgres' (admin) for DDL; table-level grants to this role are issued by V1__baseline.sql.
CREATE ROLE myfinance_app WITH LOGIN PASSWORD 'myfinance_app';
GRANT CONNECT ON DATABASE myfinance TO myfinance_app;
