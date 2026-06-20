-- "Same declaration already uploaded": a second declaration of the same type for the same fiscal
-- period (and company) is flagged a duplicate, so it isn't double-counted in the payment email.
ALTER TABLE tax_declaration ADD COLUMN duplicate boolean NOT NULL DEFAULT false;
