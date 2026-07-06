-- V30: Close two RLS gaps found in the tenancy audit.
--
-- 1) outbox_message was intentionally left outside RLS (V1) with a nullable tenant_id, yet the
--    RLS-subject app role holds full CRUD on it — so an ordinary tenant connection could read/write
--    another tenant's outbox rows, violating the golden rule (every tenant table is tenant-scoped).
--    Bring it under the same fail-closed policy as every other table, with a SUPER_ADMIN escape hatch
--    so the cross-tenant relay (V1 TODO: dedicated SYSTEM role) can still drain all pending rows.
--    The table is empty and currently unwritten (the outbox producer/relay is pending, S4), so adding
--    NOT NULL + FORCE RLS now is safe and forward-compatible.
--
-- 2) import_file and source_connection (V27) got a USING clause but no WITH CHECK, so an UPDATE could
--    move a row's tenant_id to another tenant without being rejected at write time. Recreate their
--    policies with both clauses to match every other tenant table.

-- ---- 1) outbox_message ------------------------------------------------------
DELETE FROM outbox_message WHERE tenant_id IS NULL;  -- unrouteable rows (none expected)
ALTER TABLE outbox_message ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE outbox_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox_message FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON outbox_message;
CREATE POLICY tenant_isolation ON outbox_message
    USING (
        current_setting('app.role', true) = 'SUPER_ADMIN'
        OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.role', true) = 'SUPER_ADMIN'
        OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
    );

-- ---- 2) add the missing WITH CHECK to import_file / source_connection --------
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['import_file','source_connection'] LOOP
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I;', t);
        EXECUTE format($f$
            CREATE POLICY tenant_isolation ON %I
                USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
                WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
        $f$, t);
    END LOOP;
END $$;
