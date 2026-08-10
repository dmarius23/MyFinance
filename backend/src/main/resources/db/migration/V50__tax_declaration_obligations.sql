-- Persist the itemized fiscal obligations (creanțe) parsed from each declaration's ANAF XML, so the
-- monthly Tax & Payments list can show one line per obligation (code + short label + amount) without
-- re-parsing every PDF on each load. Each element: {"cod": "628", "amount": 1234.00}. NULL for
-- declarations stored before this change — the list falls back to the document total for those.
ALTER TABLE tax_declaration ADD COLUMN obligations jsonb;
