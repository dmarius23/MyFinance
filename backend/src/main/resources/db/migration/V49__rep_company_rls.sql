-- Defense-in-depth: narrow a REPRESENTATIVE to the companies they are linked to (representative_link),
-- on top of the existing permissive tenant_isolation policy. Staff, super-admins and background/system
-- contexts (role <> 'REPRESENTATIVE') are unaffected. Backs the app-layer scoping in PortalService.
--
-- RESTRICTIVE so it AND-combines with tenant_isolation — a second *permissive* policy would OR and thus
-- WIDEN access. No explicit WITH CHECK: for reps the USING expression is applied to writes too, which is
-- exactly right (a rep only ever uploads/derives rows for their own linked company); staff pass trivially.
--
-- The set of tables is discovered from the catalog (every base table with a company_id column), so it
-- self-maintains as the schema grows. representative_link is excluded: this policy's own subquery reads
-- it, so a policy on it would recurse. app.user_id is set per connection by RlsDataSource.
DO $$
DECLARE
    t text;
BEGIN
    FOR t IN
        SELECT c.table_name
        FROM information_schema.columns c
        JOIN information_schema.tables tb
          ON tb.table_schema = c.table_schema AND tb.table_name = c.table_name
        WHERE c.table_schema = 'public'
          AND c.column_name = 'company_id'
          AND tb.table_type = 'BASE TABLE'
          AND c.table_name <> 'representative_link'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS rep_company_scope ON %I;', t);
        EXECUTE format($f$
            CREATE POLICY rep_company_scope ON %I
                AS RESTRICTIVE
                USING (
                    nullif(current_setting('app.role', true), '') IS DISTINCT FROM 'REPRESENTATIVE'
                    OR company_id IN (
                        SELECT rl.company_id FROM representative_link rl
                        WHERE rl.user_id = nullif(current_setting('app.user_id', true), '')::uuid
                    )
                );
        $f$, t);
    END LOOP;

    -- The company table itself is keyed on id, not company_id.
    DROP POLICY IF EXISTS rep_company_scope ON company;
    CREATE POLICY rep_company_scope ON company
        AS RESTRICTIVE
        USING (
            nullif(current_setting('app.role', true), '') IS DISTINCT FROM 'REPRESENTATIVE'
            OR id IN (
                SELECT rl.company_id FROM representative_link rl
                WHERE rl.user_id = nullif(current_setting('app.user_id', true), '')::uuid
            )
        );
END $$;
