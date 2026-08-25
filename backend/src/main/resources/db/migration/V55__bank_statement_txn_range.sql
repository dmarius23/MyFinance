-- Reconcile-by-month: a statement file covers a date range, and each file keeps its OWN transactions
-- (store-then-dedupe-at-view). Record the file's real covered range so the per-month files list can label
-- and place a multi-month statement under every month it touches.
ALTER TABLE bank_statement ADD COLUMN first_txn_date date;
ALTER TABLE bank_statement ADD COLUMN last_txn_date  date;

-- Read the month view by transaction date (not by the statement's dominant month).
CREATE INDEX idx_bank_txn_company_date ON bank_transaction (company_id, txn_date);
