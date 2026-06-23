-- =====================================================================
-- MOD-10 Internal Tasks: firm-staff to-do board (TODO/IN_PROGRESS/DONE),
-- optional assignee and company link, optional due date. Tenant-scoped.
-- =====================================================================

CREATE TABLE task (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    title       text NOT NULL,
    details     text,
    assignee_id uuid,
    company_id  uuid,
    due_date    date,
    status      text NOT NULL DEFAULT 'TODO',     -- TODO / IN_PROGRESS / DONE
    created_by  uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_task_tenant_status ON task(tenant_id, status);
CREATE INDEX idx_task_assignee ON task(tenant_id, assignee_id);

ALTER TABLE task ENABLE ROW LEVEL SECURITY;
ALTER TABLE task FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON task
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON task TO myfinance_app;
    END IF;
END $$;
