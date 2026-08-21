-- =====================================================================
-- MOD-15 — per (module + month) Drive-sync status: when it last ran and
-- whether a sync is running right now. Persisted so an in-progress sync
-- (whoever triggered it) is visible to every user of the tenant, and each
-- module screen can show "last synced" for the selected month.
-- =====================================================================

CREATE TABLE module_sync_status (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    module         text NOT NULL,                 -- PAYROLL / DECLARATION / TRIAL_BALANCE
    period_month   date NOT NULL,
    running        boolean NOT NULL DEFAULT false,
    started_at     timestamptz,
    started_by     uuid,
    last_synced_at timestamptz,
    last_result    text,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_module_sync_status ON module_sync_status(tenant_id, module, period_month);

ALTER TABLE module_sync_status ENABLE ROW LEVEL SECURITY;
ALTER TABLE module_sync_status FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON module_sync_status
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON module_sync_status TO myfinance_app;
    END IF;
END $$;
