-- Run ONCE in the Supabase SQL editor (as the project 'postgres' role) BEFORE the first backend deploy.
--
-- Managed Supabase has no docker-entrypoint, so the RLS-subject application role that
-- infra/db/init/01-init-roles.sql creates for local docker must be created here by hand.
--
-- This role is intentionally NOT a superuser and has NO BYPASSRLS, so PostgreSQL row-level security
-- actually constrains it. Flyway connects as the admin 'postgres' role for DDL; the per-table GRANTs to
-- this role are issued by the V1__baseline.sql migration on the first backend boot.
--
-- STEPS:
--   1) Replace CHANGE_ME_STRONG_PASSWORD below with a strong password.
--   2) Put the SAME password in deploy/.env as DB_APP_PASSWORD.

do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'myfinance_app') then
    create role myfinance_app with login password 'CHANGE_ME_STRONG_PASSWORD';
  end if;
end
$$;

-- Supabase's default database is named "postgres".
grant connect on database postgres to myfinance_app;

-- Access the public schema; the per-table privileges come from the Flyway V1 baseline on first boot.
grant usage on schema public to myfinance_app;
