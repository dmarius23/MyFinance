-- =====================================================================
-- Global reference settings — Phase 3 (destructive cleanup).
--
-- Tax rates and treasury accounts now live in the global, effective-dated,
-- SUPER_ADMIN-managed tables (platform_tax_rate / platform_treasury_account,
-- V35). Reads have been switched over (TaxPaymentService in Phase 1; the tenant
-- Settings page reads them read-only in Phase 3), so the per-tenant copies are
-- redundant and are removed here.
--
-- general_settings keeps only its per-tenant column: sender_email (+ tenant_id
-- PK and updated_at). The residence_treasury table is dropped entirely — its
-- data was seeded into platform_treasury_account by V35.
-- =====================================================================

ALTER TABLE general_settings
    DROP COLUMN vat_rate,
    DROP COLUMN micro_rate,
    DROP COLUMN profit_rate;

DROP TABLE residence_treasury;
