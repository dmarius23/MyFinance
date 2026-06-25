-- =====================================================================
-- Create a TEST representative to verify the PWA portal.
-- Auth lives in Supabase (cloud); app data lives in the local Postgres.
-- The two are linked by the SAME user UUID on both sides.
-- =====================================================================

-- STEP 1 — Supabase dashboard (cloud): Authentication → Users → "Add user"
--   email:    rep@innovatecode.test   (or any email NOT already used)
--   password: <choose one — you'll log in with it>
--   ✔ Auto Confirm User
--   Then copy the new user's UUID.

-- STEP 2 — Supabase SQL editor (cloud DB, as postgres): give the rep its claims.
--   Replace <REP_AUTH_UUID>.
update auth.users
   set raw_app_meta_data = coalesce(raw_app_meta_data, '{}'::jsonb) || jsonb_build_object(
         'tenant_id',  '00000000-0000-0000-0000-000000000001',
         'role',       'REPRESENTATIVE',
         'company_id', '3baa1377-dd8c-449e-9a36-d2f94c89a904')   -- INNOVATECODE IT SRL
 where id = '<REP_AUTH_UUID>';

-- STEP 3 — LOCAL app database (localhost:5432/myfinance, e.g. `psql -U marius -d myfinance`):
--   the app_user.id MUST equal the Supabase auth UUID from step 1.
insert into app_user (id, tenant_id, email, name, role, status)
values ('<REP_AUTH_UUID>', '00000000-0000-0000-0000-000000000001',
        'rep@innovatecode.test', 'Rep Test', 'REPRESENTATIVE', 'ACTIVE')
on conflict (id) do update set role = 'REPRESENTATIVE', status = 'ACTIVE';

insert into representative_link (tenant_id, user_id, company_id)
values ('00000000-0000-0000-0000-000000000001', '<REP_AUTH_UUID>',
        '3baa1377-dd8c-449e-9a36-d2f94c89a904')
on conflict do nothing;

-- Also required: the Custom Access Token Hook must be enabled (supabase/access_token_hook.sql),
-- so the JWT carries tenant_id/role/company_id. Then log in at the app URL with the rep's
-- email + password → you'll land on /portal scoped to INNOVATECODE IT SRL.
