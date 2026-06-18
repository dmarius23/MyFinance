-- =====================================================================
-- Receipt extraction (MOD-05, phase A): photographed receipts (bon fiscal)
-- are stored as invoice rows. Beyond supplier_name/total/invoice_date they
-- carry a few receipt-specific identifiers used for validation and dedup.
-- =====================================================================

ALTER TABLE invoice
    ADD COLUMN issuer_cif     text,   -- merchant fiscal code (CIF/CUI)
    ADD COLUMN client_cif     text,   -- buyer fiscal code (ties the receipt to this company)
    ADD COLUMN receipt_number text;   -- BF / AMEF / RL number (reliable duplicate key)
