-- =====================================================================
-- S6: ShedLock lock table — the distributed lock that makes the ingestion
-- Drive poll run EXACTLY ONCE per tick no matter how many web/worker
-- instances are running (each instance tries to grab the named lock; only
-- one wins; the rest skip the tick). Cross-instance infrastructure, NOT
-- tenant data — it has no tenant_id and is intentionally OUTSIDE RLS (the
-- golden-rule "every tenant table has RLS" does not apply here). Written by
-- the admin datasource (see DataSourceConfig#adminJdbcTemplate); the grant
-- is kept for completeness in case the app role ever uses it.
-- Standard ShedLock JDBC schema (name PK; DB-clock timestamps).
-- =====================================================================

CREATE TABLE shedlock (
    name       varchar(64)  NOT NULL,
    lock_until timestamp    NOT NULL,
    locked_at  timestamp    NOT NULL,
    locked_by  varchar(255) NOT NULL,
    PRIMARY KEY (name)
);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON shedlock TO myfinance_app;
    END IF;
END $$;
