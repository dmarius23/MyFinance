-- =====================================================================
-- Wrong-party verdict per invoice/receipt: is the document addressed to
-- THIS company? For invoices it's derived from the extracted client CIF vs
-- the company CUI; for receipts the vision model compares the printed CIF
-- CLIENT to the company CUI (robust to single-digit OCR misreads). NULL =
-- undetermined (no client CIF / can't tell).
-- =====================================================================

ALTER TABLE invoice
    ADD COLUMN wrong_party boolean;
