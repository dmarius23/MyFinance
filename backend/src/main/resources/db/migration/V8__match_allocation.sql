-- =====================================================================
-- Richer reconciliation, slice 1: per-link allocation amount.
--
-- A transaction↔invoice match now records HOW MUCH of the payment applies
-- to that invoice. This makes two cases first-class:
--   * one invoice paid by several transactions (partial payments / installments)
--   * one transaction paying several invoices (split payment)
-- Invoice "remaining" = total - Σ allocations; transaction "remaining" =
-- |amount| - Σ allocations. Existing matches are exact 1:1, so backfill each
-- with the full invoice total (falling back to the transaction amount when the
-- invoice total is unknown, e.g. image-only receipts linked manually).
-- =====================================================================

ALTER TABLE transaction_invoice_match
    ADD COLUMN allocated_amount numeric(15,2) NOT NULL DEFAULT 0;

UPDATE transaction_invoice_match m
   SET allocated_amount = COALESCE(i.total_amount, ABS(t.amount), 0)
  FROM invoice i, bank_transaction t
 WHERE m.invoice_id = i.id
   AND m.transaction_id = t.id;
