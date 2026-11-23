-- Currency for invoices and bank transactions.
--
-- The system had no concept of currency: MatchSuggester compared raw amounts within a tolerance and no
-- currency check, so a EUR 59.90 invoice could be suggested against a RON 59.90 payment — at ~5 RON/EUR,
-- two different payments. Clients buy abroad (Bolt rides, foreign hotels), so EUR invoices are routine.
--
-- NOT NULL DEFAULT 'RON' backfills every existing row in this one statement, which is intended: the
-- system has been RON-only in practice. It also keeps the column free of a null-means-RON rule that
-- every future reader would have to know.
ALTER TABLE invoice
    ADD COLUMN currency char(3) NOT NULL DEFAULT 'RON'
        CHECK (currency ~ '^[A-Z]{3}$');

ALTER TABLE bank_transaction
    ADD COLUMN currency char(3) NOT NULL DEFAULT 'RON'
        CHECK (currency ~ '^[A-Z]{3}$');
