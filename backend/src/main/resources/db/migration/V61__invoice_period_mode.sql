-- Tenant preference for assigning email-imported invoices to a reporting month.
ALTER TABLE general_settings
    ADD COLUMN invoice_period_mode varchar(32) NOT NULL DEFAULT 'INVOICE_DATE';

ALTER TABLE general_settings
    ADD CONSTRAINT general_settings_invoice_period_mode_check
    CHECK (invoice_period_mode IN ('INVOICE_DATE', 'PREVIOUS_MONTH'));
