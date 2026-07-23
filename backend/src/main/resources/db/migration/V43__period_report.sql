-- =====================================================================
-- MOD-06 Reports: cached aggregated reports for calendar periods wider than
-- a month (quarter / half-year / year). Each row is folded from the monthly
-- trial_balance snapshots it encloses and keyed by a source fingerprint so a
-- re-uploaded month invalidates the enclosing period. report_json holds the
-- aggregated ReportData. MONTH is never stored here (served from trial_balance).
-- =====================================================================

CREATE TABLE period_report (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid NOT NULL REFERENCES tenant(id),
    company_id         uuid NOT NULL,
    granularity        text NOT NULL,              -- QUARTER / HALF / YEAR
    period_start       date NOT NULL,              -- first day of the calendar period
    report_json        text NOT NULL,
    complete           boolean NOT NULL,
    months_present     int  NOT NULL,
    months_expected    int  NOT NULL,
    source_fingerprint text NOT NULL,              -- hash of the constituent (month, version, content_period)
    generated_at       timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_period_report_company_period
    ON period_report(tenant_id, company_id, granularity, period_start);

ALTER TABLE period_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE period_report FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON period_report
    USING  (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON period_report TO myfinance_app;
    END IF;
END $$;
