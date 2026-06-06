CREATE TABLE bank_statement (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenant(id),
    document_id     uuid NOT NULL UNIQUE REFERENCES document(id) ON DELETE CASCADE,
    company_id      uuid NOT NULL REFERENCES company(id),
    period_month    date NOT NULL,
    bank_code       text,
    account_iban    text,
    opening_balance numeric(15,2),
    closing_balance numeric(15,2),
    status          text NOT NULL,
    cross_check_ok  boolean NOT NULL DEFAULT false,
    txn_count       int NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE bank_transaction (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenant(id),
    company_id          uuid NOT NULL REFERENCES company(id),
    statement_id        uuid NOT NULL REFERENCES bank_statement(id) ON DELETE CASCADE,
    txn_date            date NOT NULL,
    amount              numeric(15,2) NOT NULL,
    direction           text NOT NULL,
    partner_name        text,
    partner_iban        text,
    description         text,
    ref                 text,
    balance_after       numeric(15,2),
    matched_document_id uuid REFERENCES document(id),
    requires_document   boolean NOT NULL DEFAULT false,
    decision_source     text,
    category            text,
    override_reason     text
);
CREATE INDEX idx_bank_txn_company ON bank_transaction(tenant_id, company_id);
CREATE INDEX idx_bank_txn_statement ON bank_transaction(statement_id);

ALTER TABLE bank_statement ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_statement FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bank_statement
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE bank_transaction ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_transaction FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bank_transaction
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON bank_statement TO myfinance_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON bank_transaction TO myfinance_app;
    END IF;
END $$;
