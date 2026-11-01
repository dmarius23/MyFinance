-- The statutory day-of-month on which salary taxes (D112) fall due, in the month FOLLOWING the period.
-- Effective-dated: the greatest valid_from <= period wins, so a statutory change never retroactively
-- alters an earlier period.
--
-- GLOBAL reference data: deliberately NO tenant_id and NO row-level security. This is identical public
-- Romanian reference data for every tenant, exactly like platform_tax_rate and platform_treasury_account
-- (see V35) — so it is a DOCUMENTED EXCEPTION to golden rule #1 ("every tenant table has tenant_id + RLS
-- + a cross-tenant isolation test"), not an oversight. Writes are gated at the application layer by
-- @PreAuthorize("hasRole('SUPER_ADMIN')") on PlatformReferenceController; a shared DB role cannot
-- distinguish SUPER_ADMIN, so authz lives in the app as it does for the sibling reference tables. The
-- mandatory isolation test is replaced by an authz test asserting a non-SUPER_ADMIN gets 403.
CREATE TABLE platform_tax_deadline (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    day_of_month smallint NOT NULL CHECK (day_of_month BETWEEN 1 AND 28),
    valid_from   date NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_platform_tax_deadline UNIQUE (valid_from)
);

-- 1..28 rather than 1..31: a day of 29-31 would silently move in February. If a statute ever names the
-- 30th, that needs an explicit end-of-month clamping rule, decided deliberately.

-- Seed the rule in force today so every existing period resolves and no consumer needs a special case.
INSERT INTO platform_tax_deadline (day_of_month, valid_from) VALUES (25, '2020-01-01');

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON platform_tax_deadline TO myfinance_app;
    END IF;
END $$;
