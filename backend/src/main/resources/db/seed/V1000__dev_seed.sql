-- Dev-only demo data. Loaded only under the 'local' profile (see application-local.yml,
-- spring.flyway.locations). Sets the RLS session GUCs so inserts satisfy the policies.
SET LOCAL app.role = 'SUPER_ADMIN';
SET LOCAL app.tenant_id = '00000000-0000-0000-0000-000000000001';

INSERT INTO tenant (id, name, cui, status, plan)
VALUES ('00000000-0000-0000-0000-000000000001', 'Demo Contabilitate SRL', 'RO12345678', 'ACTIVE', 'STANDARD')
ON CONFLICT (id) DO NOTHING;

INSERT INTO app_user (id, tenant_id, email, name, role, status)
VALUES
  ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000001', 'admin@demo.ro',    'Ana Admin',     'TENANT_ADMIN', 'ACTIVE'),
  ('00000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-000000000001', 'employee@demo.ro', 'Emil Angajat',  'EMPLOYEE',     'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO company (id, tenant_id, legal_name, entity_type, cui, locality, vat_status, vat_period, responsible_user_id, status)
VALUES
  ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-000000000001', 'Client Unu SRL', 'SRL', 'RO22223333', 'Cluj-Napoca', 'VAT_PAYER', 'MONTHLY',   '00000000-0000-0000-0000-0000000000a2', 'ACTIVE'),
  ('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-000000000001', 'Client Doi SRL', 'SRL', 'RO44445555', 'Bucuresti',    'NON_VAT_PAYER', 'QUARTERLY', '00000000-0000-0000-0000-0000000000a2', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO treasury_account (id, tenant_id, company_id, tax_type, locality, iban, label)
VALUES ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000c1', 'VAT', 'Cluj-Napoca', 'RO49AAAA1B31007593840000', 'TVA Cluj')
ON CONFLICT (id) DO NOTHING;
