-- =====================================================================
-- Bootstrap the FIRST tenant + admin (one-time, manual).
-- Invites require an existing admin, so the very first one is seeded by hand.
-- Run in the SQL editor as the `postgres` role (it bypasses RLS).
-- =====================================================================

-- 1) Create the auth user first:
--    Dashboard → Authentication → Users → "Add user" (or Invite).
--    Copy the user's UUID and email into the placeholders below.

-- 2) Tell the access-token hook who they are (tenant + admin role):
--    Replace <AUTH_USER_UUID> and <TENANT_UUID> (generate one, e.g. gen_random_uuid()).
update auth.users
   set raw_app_meta_data = coalesce(raw_app_meta_data, '{}'::jsonb)
       || jsonb_build_object('tenant_id', '<TENANT_UUID>', 'role', 'TENANT_ADMIN')
 where id = '<AUTH_USER_UUID>';

-- 3) Insert the matching tenant + app_user rows (app_user.id MUST equal the auth user id).
insert into tenant (id, name, cui, plan)
values ('<TENANT_UUID>', 'Your Accounting Firm SRL', 'RO00000000', 'STANDARD')
on conflict (id) do nothing;

insert into app_user (id, tenant_id, email, name, role, status)
values ('<AUTH_USER_UUID>', '<TENANT_UUID>', '<ADMIN_EMAIL>', 'Firm Admin', 'TENANT_ADMIN', 'ACTIVE')
on conflict (id) do nothing;

-- After this, that user can log in as a TENANT_ADMIN and invite the rest of the team
-- (admins/accountants) from the in-app Team page — those invites go through Supabase automatically.
