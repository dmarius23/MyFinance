-- =====================================================================
-- S4d (queue hardening): give the outbox relay multi-worker single-flight
-- and a visibility timeout. Delivery now claims due rows atomically with
-- SELECT ... FOR UPDATE SKIP LOCKED, flipping them to a transient PROCESSING
-- state so a second worker never picks the same message. If a worker dies
-- mid-delivery its row is stuck PROCESSING; a reaper resets rows whose
-- claimed_at is older than the visibility timeout back to PENDING.
-- Status values are now: PENDING | PROCESSING | SENT | DLQ.
-- =====================================================================

ALTER TABLE outbox_message ADD COLUMN claimed_at timestamptz;

-- Reaper lookup: PROCESSING rows past their visibility window.
CREATE INDEX idx_outbox_processing ON outbox_message(claimed_at) WHERE status = 'PROCESSING';
