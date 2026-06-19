-- =====================================================================
-- Treasury accounts move from (county, single tax type) to (residence,
-- one IBAN covering MULTIPLE tax types). Residence is a city/town (fiscal
-- residence). tax_types is a comma-separated list of tax-type codes.
-- Existing rows are migrated by grouping on (tenant, county, iban) so the
-- same IBAN with several taxes collapses into one account.
-- =====================================================================

CREATE TABLE residence_treasury_account (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid NOT NULL REFERENCES tenant(id),
    residence  text NOT NULL,
    iban       text NOT NULL,
    label      text,
    tax_types  text NOT NULL DEFAULT ''   -- comma-separated tax-type codes
);
CREATE INDEX idx_residence_treasury_tenant ON residence_treasury_account(tenant_id);

-- Migrate existing county/tax-type rows: one account per (tenant, county, iban) with all its taxes.
INSERT INTO residence_treasury_account (tenant_id, residence, iban, label, tax_types)
SELECT tenant_id, county, iban, max(label), string_agg(DISTINCT tax_type, ',' ORDER BY tax_type)
FROM county_treasury_account
GROUP BY tenant_id, county, iban;

ALTER TABLE residence_treasury_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE residence_treasury_account FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON residence_treasury_account
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON residence_treasury_account TO myfinance_app;
    END IF;
END $$;

DROP TABLE county_treasury_account;
