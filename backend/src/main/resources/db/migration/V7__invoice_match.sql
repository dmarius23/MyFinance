CREATE TABLE invoice (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenant(id),
    document_id       uuid NOT NULL UNIQUE REFERENCES document(id) ON DELETE CASCADE,
    company_id        uuid NOT NULL REFERENCES company(id),
    period_month      date NOT NULL,
    supplier_name     text,
    supplier_iban     text,
    total_amount      numeric(15,2),
    invoice_date      date,
    original_filename text,
    status            text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_invoice_company_period ON invoice(tenant_id, company_id, period_month);

CREATE TABLE transaction_invoice_match (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    transaction_id uuid NOT NULL REFERENCES bank_transaction(id) ON DELETE CASCADE,
    invoice_id     uuid NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,
    source         text NOT NULL,
    created_by     uuid,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_txn_invoice UNIQUE (transaction_id, invoice_id)
);
CREATE INDEX idx_tim_transaction ON transaction_invoice_match(transaction_id);

ALTER TABLE invoice ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE transaction_invoice_match ENABLE ROW LEVEL SECURITY;
ALTER TABLE transaction_invoice_match FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON transaction_invoice_match
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON invoice TO myfinance_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON transaction_invoice_match TO myfinance_app;
    END IF;
END $$;
