ALTER TABLE bank_transaction ADD COLUMN account_iban text;

CREATE TABLE transaction_rule (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenant(id),
    company_id        uuid NOT NULL REFERENCES company(id),
    match_iban        text,
    match_desc_norm   text NOT NULL,
    requires_document boolean NOT NULL,
    created_by        uuid,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_txn_rule UNIQUE (tenant_id, company_id, match_iban, match_desc_norm)
);
CREATE INDEX idx_txn_rule_company ON transaction_rule(tenant_id, company_id);

ALTER TABLE transaction_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE transaction_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON transaction_rule
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON transaction_rule TO myfinance_app;
    END IF;
END $$;
