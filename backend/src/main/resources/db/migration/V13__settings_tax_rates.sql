-- Tenant-level default tax rates used when composing state-payment figures: micro (impozit pe venit)
-- and profit (impozit pe profit). Defaults to the current statutory values.
ALTER TABLE general_settings
    ADD COLUMN micro_rate  numeric(5,2) NOT NULL DEFAULT 3.00,
    ADD COLUMN profit_rate numeric(5,2) NOT NULL DEFAULT 16.00;
