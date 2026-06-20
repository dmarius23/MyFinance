-- Declaration flags for the manager modal: the declaration's OWN period (from its XML, to detect a
-- document filed under the wrong month) and whether its CUI matches the company (wrong party).
ALTER TABLE tax_declaration ADD COLUMN decl_period date;
ALTER TABLE tax_declaration ADD COLUMN wrong_party boolean NOT NULL DEFAULT false;
