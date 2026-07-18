-- =====================================================================
-- Global reference settings — tax rates + treasury accounts (Phase 1).
--
-- Tax rates (VAT / MICRO / PROFIT) and treasury IBANs are NATIONAL Romanian
-- reference data, identical for every tenant. Until now they lived per-tenant
-- (general_settings + residence_treasury), so every firm re-entered them and
-- they could drift. These two tables make them GLOBAL and effective-dated,
-- maintained by SUPER_ADMIN; tenants read them.
--
-- GOLDEN RULE #1 EXCEPTION (intentional): these tables have NO tenant_id and
-- NO row-level security. That rule exists to stop cross-tenant leakage of
-- tenant data — but this is public national reference data shared by everyone,
-- so there is nothing to isolate. Writes are gated at the app layer by
-- @PreAuthorize("hasRole('SUPER_ADMIN')") (a shared DB role can't distinguish
-- SUPER_ADMIN, exactly as authz is enforced everywhere else). Do NOT add RLS
-- or a cross-tenant isolation test here — it would be meaningless.
--
-- Phase 1 only creates + seeds these tables and switches READS to them. The
-- old per-tenant general_settings rate columns and the residence_treasury
-- table are dropped later, in Phase 3, once this is verified in the running app.
-- =====================================================================

-- --- Tax rates -------------------------------------------------------
CREATE TABLE platform_tax_rate (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    category   text NOT NULL,             -- VAT | MICRO | PROFIT
    rate       numeric(6,2) NOT NULL,
    valid_from date NOT NULL,             -- effective-dated: greatest valid_from <= period wins
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_platform_tax_rate UNIQUE (category, valid_from)
);

-- --- Treasury accounts ----------------------------------------------
CREATE TABLE platform_treasury_account (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    residence     text NOT NULL,          -- fiscal residence (city/town)
    iban_cam      text,
    iban_impozite text,
    iban_cass     text,
    iban_cas      text,
    iban_tva      text,
    valid_from    date NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_platform_treasury UNIQUE (residence, valid_from)
);

-- --- Seed rates -----------------------------------------------------
-- Current national statutory values at an early valid_from so every existing
-- period resolves. Real historical transitions (e.g. VAT 19 -> 21) are NOT
-- invented here — the super-admin adds them with their own valid_from.
INSERT INTO platform_tax_rate (category, rate, valid_from) VALUES
    ('VAT',    21.00, DATE '2020-01-01'),
    ('MICRO',   3.00, DATE '2020-01-01'),
    ('PROFIT', 16.00, DATE '2020-01-01');

-- --- Seed treasury --------------------------------------------------
-- One global row per distinct residence, coalescing each per-category IBAN
-- across all tenants' residence_treasury rows (they are national, so identical
-- where present). Flyway runs as the admin role and bypasses RLS, so this sees
-- every tenant's rows. If no tenant has any treasury rows yet this is a no-op.
INSERT INTO platform_treasury_account
    (residence, iban_cam, iban_impozite, iban_cass, iban_cas, iban_tva, valid_from)
SELECT residence,
       max(iban_cam), max(iban_impozite), max(iban_cass), max(iban_cas), max(iban_tva),
       DATE '2020-01-01'
FROM residence_treasury
GROUP BY residence;

-- --- Grants (no RLS) ------------------------------------------------
-- The app role gets full DML; write authorization is enforced in the app layer
-- (SUPER_ADMIN only), consistent with the rest of the system.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON platform_tax_rate TO myfinance_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON platform_treasury_account TO myfinance_app;
    END IF;
END $$;
