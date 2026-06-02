-- =====================================================================
-- MyFinance — baseline schema (foundation: MOD-01 tenant, MOD-02 access,
-- MOD-03 company) + cross-cutting audit & outbox.
--
-- Golden rule: every tenant-scoped table carries tenant_id and is protected
-- by row-level security keyed on the per-connection GUC app.tenant_id, which
-- RlsDataSource sets from the validated JWT. RLS only constrains a role that
-- is itself subject to it (non-superuser, non-BYPASSRLS) — the app connects as
-- myfinance_app; Flyway runs this DDL as the admin role.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

-- ---------------------------------------------------------------------
-- MOD-01  tenant
-- ---------------------------------------------------------------------
CREATE TABLE tenant (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text        NOT NULL,
    cui         text,
    status      text        NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | SUSPENDED | ARCHIVED
    plan        text        NOT NULL DEFAULT 'STANDARD',
    limits      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    branding    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- MOD-02  app_user, representative_link
-- ---------------------------------------------------------------------
CREATE TABLE app_user (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant(id),
    email       text        NOT NULL,
    name        text,
    role        text        NOT NULL,                    -- SUPER_ADMIN | TENANT_ADMIN | EMPLOYEE | REPRESENTATIVE
    status      text        NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | INACTIVE
    mfa_enabled boolean     NOT NULL DEFAULT false,
    last_login  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_email_per_tenant UNIQUE (tenant_id, email)
);
CREATE INDEX idx_app_user_tenant ON app_user(tenant_id);

-- ---------------------------------------------------------------------
-- MOD-03  company + children
-- ---------------------------------------------------------------------
CREATE TABLE company (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid        NOT NULL REFERENCES tenant(id),
    legal_name          text        NOT NULL,
    entity_type         text,                            -- SRL | PFA | SA | ...
    cui                 text        NOT NULL,
    reg_no              text,
    address             text,
    locality            text,
    vat_status          text,                            -- e.g. VAT_PAYER | NON_PAYER
    vat_period          text,                            -- MONTHLY | QUARTERLY
    tax_regime          text,
    responsible_user_id uuid        REFERENCES app_user(id),
    status              text        NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | INACTIVE
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_company_cui_per_tenant UNIQUE (tenant_id, cui)
);
CREATE INDEX idx_company_tenant ON company(tenant_id);

CREATE TABLE company_contact (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    company_id  uuid NOT NULL REFERENCES company(id) ON DELETE CASCADE,
    name        text NOT NULL,
    email       text,
    phone       text,
    role        text
);
CREATE INDEX idx_company_contact_company ON company_contact(company_id);

CREATE TABLE company_employee (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenant(id),
    company_id    uuid NOT NULL REFERENCES company(id) ON DELETE CASCADE,
    name          text NOT NULL,
    role          text,
    hired_on      date,
    terminated_on date,
    status        text NOT NULL DEFAULT 'ACTIVE'
);
CREATE INDEX idx_company_employee_company ON company_employee(company_id);

CREATE TABLE treasury_account (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    company_id  uuid NOT NULL REFERENCES company(id) ON DELETE CASCADE,
    tax_type    text NOT NULL,
    locality    text,
    iban        text NOT NULL,
    label       text
);
CREATE INDEX idx_treasury_account_company ON treasury_account(company_id);

CREATE TABLE representative_link (
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    user_id     uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    company_id  uuid NOT NULL REFERENCES company(id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, company_id)
);
CREATE INDEX idx_rep_link_tenant ON representative_link(tenant_id);

-- ---------------------------------------------------------------------
-- MOD-12  audit_entry (append-only)
-- ---------------------------------------------------------------------
CREATE TABLE audit_entry (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL,
    actor_id    uuid,
    actor_role  text,
    action      text        NOT NULL,
    entity      text,
    entity_id   uuid,
    before      jsonb,
    after       jsonb,
    channel     text,
    at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_tenant_at ON audit_entry(tenant_id, at DESC);

-- ---------------------------------------------------------------------
-- Outbox (cross-cutting infrastructure). Written inside the tenant
-- transaction, relayed by the worker. Intentionally NOT under RLS: the
-- relay is cross-tenant system infrastructure (tenant_id retained for
-- routing/audit). TODO(MOD-09): relay connects with a dedicated SYSTEM role.
-- ---------------------------------------------------------------------
CREATE TABLE outbox_message (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid,
    aggregate_type  text        NOT NULL,
    aggregate_id    text,
    type            text        NOT NULL,
    payload         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status          text        NOT NULL DEFAULT 'PENDING',  -- PENDING | SENT | FAILED
    attempts        int         NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    sent_at         timestamptz
);
CREATE INDEX idx_outbox_pending ON outbox_message(status, created_at) WHERE status = 'PENDING';

-- =====================================================================
-- Row-level security
-- =====================================================================
-- Helper expression: nullif(current_setting('app.tenant_id', true), '')::uuid
-- yields NULL when unset, so every comparison is false → zero rows (fail closed).

-- tenant: a tenant admin sees only their own row; SUPER_ADMIN sees all.
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant
    USING (
        current_setting('app.role', true) = 'SUPER_ADMIN'
        OR id = nullif(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        current_setting('app.role', true) = 'SUPER_ADMIN'
        OR id = nullif(current_setting('app.tenant_id', true), '')::uuid
    );

-- All other tenant-scoped tables: strict tenant_id match.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'app_user','company','company_contact','company_employee',
        'treasury_account','representative_link','audit_entry'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY;', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY;', t);
        EXECUTE format($f$
            CREATE POLICY tenant_isolation ON %I
                USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
                WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
        $f$, t);
    END LOOP;
END $$;

-- =====================================================================
-- Grants for the RLS-subject application role (local dev: myfinance_app).
-- Guarded so this migration also runs cleanly where that role is absent
-- (e.g. a Supabase project that uses its own pooled role).
-- =====================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'myfinance_app') THEN
        GRANT USAGE ON SCHEMA public TO myfinance_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO myfinance_app;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public
            GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO myfinance_app;
    END IF;
END $$;
