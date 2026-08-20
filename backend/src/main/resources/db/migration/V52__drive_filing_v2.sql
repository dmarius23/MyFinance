-- =====================================================================
-- Drive Filing v2 (see docs/MyFinance-drive-filing-v2-design-v1.md).
-- The app now files uploaded documents into the firm's REAL two-drive structure:
--   * DECLARATIONS drive — declarations + payrolls + reports (root = all-years parent).
--   * ACCOUNTING   drive — bank statements + invoices (a separate shared drive).
-- A tenant therefore has up to two write-enabled Drive source connections, one per purpose.
-- Invoices are filed by direction (received vs. issued), resolved from the e-Factura XML.
-- =====================================================================

-- Which structure a Drive connection writes into. Existing rows are declarations mirrors → DECLARATIONS.
ALTER TABLE source_connection
    ADD COLUMN purpose text NOT NULL DEFAULT 'DECLARATIONS';  -- DECLARATIONS | ACCOUNTING

-- For a DECLARATIONS drive: true when root_folder_id already points at the "Declaratii {year}" folder
-- (so the app must NOT create that level); false = root is the all-years parent (app ensures the year folder).
ALTER TABLE source_connection
    ADD COLUMN root_is_year_folder boolean NOT NULL DEFAULT false;

-- Invoice filing direction, resolved from the e-Factura XML (buyer vs. supplier CUI vs. the company).
-- NULL for non-invoice documents; UNKNOWN when the invoice could not be parsed (kept in Supabase only).
ALTER TABLE document
    ADD COLUMN invoice_direction text;                       -- RECEIVED | ISSUED | UNKNOWN
