-- Runs at container init (before Flyway). Creates the RLS-subject app role used by the test
-- datasource, mirroring infra/db/init/01-init-roles.sql.
CREATE ROLE myfinance_app WITH LOGIN PASSWORD 'myfinance_app';
GRANT CONNECT ON DATABASE myfinance TO myfinance_app;
