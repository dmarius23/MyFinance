-- V29: Add content_period to trial_balance (report snapshots).
--
-- content_period stores the period extracted from the trial-balance PDF itself (the date range
-- printed in the header, e.g. "01.03.2026 -- 31.03.2026"). This is distinct from period_month,
-- which is the accounting slot the accountant uploaded it into. When the two differ the snapshot
-- is invalid for that slot and must not produce a report or charts for the representative.
--
-- Existing rows get content_period = NULL; ReportService will detect and cache it lazily on the
-- first read (one PDF parse per stale row, result stored back here so it never happens again).
--
-- Tax declarations filed for a different month than their slot are NOT purged: they are kept and
-- flagged outside-period (TaxDeclarationListener), shown in the manager with a "Move to correct
-- period" action, and excluded from that month's payment totals.

ALTER TABLE trial_balance ADD COLUMN IF NOT EXISTS content_period date;
