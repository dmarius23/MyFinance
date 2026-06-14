-- =====================================================================
-- One-time cleanup: remove redundant duplicate bank statements.
--
-- Until now the extraction dedup was scoped to the upload period, so the
-- same statement uploaded under two month-labels (or a fully-overlapping
-- re-upload) created a second bank_statement whose transactions all
-- duplicate an earlier statement's. Delete such a redundant statement
-- (the cascade removes its duplicate transactions); the kept statement
-- retains the originals and any matches on them. Deduping at the statement
-- level (rather than per-row) keeps each statement's transaction set whole.
--
-- Going forward the extraction dedup is company-wide, so a re-upload adds
-- zero transactions and this situation can't recur.
-- =====================================================================

DELETE FROM bank_statement s_dup
USING bank_statement s_keep
WHERE s_dup.company_id = s_keep.company_id
  AND s_dup.id <> s_keep.id
  -- Deterministic keeper: the earlier-created statement (tiebreak on id).
  AND (s_keep.created_at < s_dup.created_at
       OR (s_keep.created_at = s_dup.created_at AND s_keep.id < s_dup.id))
  -- s_dup must actually have transactions...
  AND EXISTS (SELECT 1 FROM bank_transaction td WHERE td.statement_id = s_dup.id)
  -- ...and every one of them must already exist in the kept statement.
  AND NOT EXISTS (
        SELECT 1 FROM bank_transaction td
        WHERE td.statement_id = s_dup.id
          AND NOT EXISTS (
              SELECT 1 FROM bank_transaction tk
              WHERE tk.statement_id = s_keep.id
                AND coalesce(tk.account_iban, '') = coalesce(td.account_iban, '')
                AND tk.txn_date = td.txn_date
                AND tk.amount = td.amount
                AND coalesce(tk.balance_after, 0) = coalesce(td.balance_after, 0)
                AND coalesce(tk.description, '') = coalesce(td.description, '')
                AND coalesce(tk.ref, '') = coalesce(td.ref, '')));

-- Resync each surviving statement's cached transaction count.
UPDATE bank_statement s
   SET txn_count = (SELECT count(*) FROM bank_transaction t WHERE t.statement_id = s.id);
