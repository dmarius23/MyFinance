-- Dev-only demo data. Loaded only under the 'local' profile (see application-local.yml,
-- spring.flyway.locations). Sets the RLS session GUCs so inserts satisfy the policies.
SET LOCAL app.role = 'SUPER_ADMIN';
SET LOCAL app.tenant_id = '00000000-0000-0000-0000-000000000001';

INSERT INTO tenant (id, name, cui, status, plan)
VALUES ('00000000-0000-0000-0000-000000000001', 'Demo Contabilitate SRL', 'RO12345678', 'ACTIVE', 'STANDARD')
ON CONFLICT (id) DO NOTHING;

INSERT INTO app_user (id, tenant_id, email, name, role, status)
VALUES
  ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000001', 'admin@demo.ro',    'Ana Admin',    'TENANT_ADMIN', 'ACTIVE'),
  ('00000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-000000000001', 'employee@demo.ro', 'Emil Angajat', 'EMPLOYEE',     'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO company (id, tenant_id, legal_name, entity_type, cui, locality, vat_status, vat_period, responsible_user_id, status)
VALUES
  ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-000000000001', 'Client Unu SRL', 'SRL', 'RO22223333', 'Cluj',       'VAT_PAYER',     'MONTHLY',   '00000000-0000-0000-0000-0000000000a2', 'ACTIVE'),
  ('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-000000000001', 'Client Doi SRL', 'SRL', 'RO44445555', 'București',   'NON_VAT_PAYER', 'QUARTERLY', '00000000-0000-0000-0000-0000000000a2', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- Rates/treasury live on global platform_* tables (V35/V36); general_settings is
-- per-tenant sender email + auto-sync only. Defaults cover auto_sync_* columns.
INSERT INTO general_settings (tenant_id, sender_email)
VALUES ('00000000-0000-0000-0000-000000000001', 'noreply@demo.ro')
ON CONFLICT (tenant_id) DO NOTHING;

-- Demo treasury row for Cluj (V35 only backfills from the old per-tenant table).
INSERT INTO platform_treasury_account
    (residence, iban_tva, iban_impozite, iban_cas, iban_cass, iban_cam, valid_from)
VALUES
  ('Cluj',
   'RO49AAAA1B31007593840000', 'RO49AAAA1B31007593840001',
   'RO49AAAA1B31007593840002', 'RO49AAAA1B31007593840003', 'RO49AAAA1B31007593840004',
   DATE '2020-01-01')
ON CONFLICT (residence, valid_from) DO NOTHING;
