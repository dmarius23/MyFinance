-- =====================================================================
-- S4a/S4b: the transactional-outbox relay now has a Java producer + worker
-- consumer. Add the two columns the relay needs on the existing
-- outbox_message table (created in V1, brought under RLS in V30):
--   * error           — last failure message (for the DLQ / diagnostics)
--   * next_attempt_at  — exponential-backoff gate; a PENDING row is only
--                        picked up once now() >= next_attempt_at
-- Status values are PENDING | SENT | DLQ (a poison message lands in DLQ
-- after the attempts cap). No RLS change — the V30 policy (tenant match OR
-- app.role = SUPER_ADMIN for the cross-tenant relay) still applies.
-- =====================================================================

ALTER TABLE outbox_message ADD COLUMN error text;
ALTER TABLE outbox_message ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();

-- The relay polls PENDING rows that are due, oldest first.
DROP INDEX IF EXISTS idx_outbox_pending;
CREATE INDEX idx_outbox_pending ON outbox_message(next_attempt_at, created_at) WHERE status = 'PENDING';
