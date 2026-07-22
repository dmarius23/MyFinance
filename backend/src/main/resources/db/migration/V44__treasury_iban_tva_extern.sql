-- =====================================================================
-- Split TVA into intern + extern on the global treasury reference table.
--
-- ANAF publishes two distinct VAT IBANs per treasury: TVA intern (budget
-- code 20A100101) and TVA extern (20A100102). Until now we stored only one
-- (iban_tva). Add iban_tva_extern so both can be resolved; iban_tva keeps
-- its meaning and now explicitly holds the INTERN account.
--
-- Same rationale as V35: this table is GLOBAL public reference data with no
-- tenant_id and no RLS — do NOT add either. The new column inherits the
-- table's existing grants, so no GRANT is needed.
-- =====================================================================

ALTER TABLE platform_treasury_account ADD COLUMN iban_tva_extern text;
