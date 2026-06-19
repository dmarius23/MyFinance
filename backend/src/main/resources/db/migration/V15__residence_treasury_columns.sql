-- =====================================================================
-- Treasury accounts restructure: one row per fiscal residence, with a
-- dedicated IBAN column per tax category — CAM, impozite (all income/
-- profit/salary/dividend taxes), CASS, CAS, TVA. Replaces the previous
-- (residence, iban, tax_types[]) model.
-- =====================================================================

CREATE TABLE residence_treasury (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    residence      text NOT NULL,
    iban_cam       text,
    iban_impozite  text,
    iban_cass      text,
    iban_cas       text,
    iban_tva       text
);
CREATE UNIQUE INDEX uq_res_treasury_tenant_residence ON residence_treasury(tenant_id, residence);
CREATE INDEX idx_res_treasury_tenant ON residence_treasury(tenant_id);

-- Migrate: collapse the old per-IBAN rows into one row per residence, routing each
-- tax type to its column. tax_types is a comma list; wrap in commas for exact-token match
-- so CAS does not also match CASS.
INSERT INTO residence_treasury (tenant_id, residence, iban_cam, iban_impozite, iban_cass, iban_cas, iban_tva)
SELECT tenant_id, residence,
    max(iban) FILTER (WHERE (',' || tax_types || ',') LIKE '%,CAM,%'),
    max(iban) FILTER (WHERE (',' || tax_types || ',') LIKE '%,IMPOZIT_PROFIT,%'
                         OR (',' || tax_types || ',') LIKE '%,IMPOZIT_MICRO,%'
                         OR (',' || tax_types || ',') LIKE '%,IMPOZIT_SALARII,%'
                         OR (',' || tax_types || ',') LIKE '%,IMPOZIT_DIVIDENDE,%'),
    max(iban) FILTER (WHERE (',' || tax_types || ',') LIKE '%,CASS,%'),
    max(iban) FILTER (WHERE (',' || tax_types || ',') LIKE '%,CAS,%'),
    max(iban) FILTER (WHERE (',' || tax_types || ',') LIKE '%,TVA,%')
FROM residence_treasury_account
GROUP BY tenant_id, residence;

ALTER TABLE residence_treasury ENABLE ROW LEVEL SECURITY;
ALTER TABLE residence_treasury FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON residence_treasury
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON residence_treasury TO myfinance_app;
    END IF;
END $$;

DROP TABLE residence_treasury_account;
