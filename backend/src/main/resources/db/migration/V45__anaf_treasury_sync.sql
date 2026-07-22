-- =====================================================================
-- Staging for the ANAF treasury-IBAN sync (review-before-apply).
--
-- A sync run scrapes ANAF and records, per treasury, the four IBANs it found
-- and how they differ from the live reference data (ADDED / CHANGED / UNCHANGED
-- / ERROR). The SUPER_ADMIN reviews the diff and then APPLIES it — only then are
-- rows written to platform_treasury_account. This gate exists because treasury
-- IBANs are payment destinations (golden rule #3: non-authoritative until verified).
--
-- Like platform_treasury_account (V35), these are GLOBAL tables: no tenant_id,
-- no RLS. Write authorization is enforced in the app layer (SUPER_ADMIN only).
-- =====================================================================

CREATE TABLE platform_treasury_sync_run (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    status           text NOT NULL,                  -- RUNNING | READY_FOR_REVIEW | APPLIED | FAILED | CANCELLED
    started_by       uuid,                           -- the SUPER_ADMIN who triggered it
    effective_from   date NOT NULL,                  -- valid_from stamped on applied rows
    counties_total   int  NOT NULL DEFAULT 0,
    treasuries_total int  NOT NULL DEFAULT 0,
    parsed_ok        int  NOT NULL DEFAULT 0,
    parse_failed     int  NOT NULL DEFAULT 0,
    notes            text,
    started_at       timestamptz NOT NULL DEFAULT now(),
    finished_at      timestamptz,
    applied_at       timestamptz
);

CREATE TABLE platform_treasury_sync_item (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id           uuid NOT NULL REFERENCES platform_treasury_sync_run (id) ON DELETE CASCADE,
    county           text,
    treasury_code    text,
    residence        text,
    source_url       text,
    iban_5503        text,
    iban_cam         text,
    iban_tva_intern  text,
    iban_tva_extern  text,
    change           text NOT NULL,                  -- ADDED | CHANGED | UNCHANGED | ERROR
    error            text,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_treasury_sync_item_run ON platform_treasury_sync_item (run_id);

-- --- Grants (no RLS) ------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON platform_treasury_sync_run TO myfinance_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON platform_treasury_sync_item TO myfinance_app;
    END IF;
END $$;
