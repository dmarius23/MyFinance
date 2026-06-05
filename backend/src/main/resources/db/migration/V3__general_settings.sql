-- Tenant-level VAT rate and other general settings. One row per tenant, created lazily.
CREATE TABLE general_settings (
    tenant_id  uuid PRIMARY KEY REFERENCES tenant(id),
    vat_rate   numeric(5,2) NOT NULL DEFAULT 21.00,
    updated_at timestamptz  NOT NULL DEFAULT now()
);

ALTER TABLE general_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE general_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON general_settings
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Per-county / per-tax-type treasury accounts (replaces per-company treasury_account).
CREATE TABLE county_treasury_account (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    county    text NOT NULL,
    tax_type  text NOT NULL,
    iban      text NOT NULL,
    label     text,
    CONSTRAINT uq_county_treasury UNIQUE (tenant_id, county, tax_type)
);
CREATE INDEX idx_county_treasury_tenant ON county_treasury_account(tenant_id);

ALTER TABLE county_treasury_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE county_treasury_account FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON county_treasury_account
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Drop per-company treasury (superseded by county_treasury_account).
DROP TABLE treasury_account;

-- Grants for the RLS-subject app role.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON general_settings TO myfinance_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON county_treasury_account TO myfinance_app;
    END IF;
END $$;
