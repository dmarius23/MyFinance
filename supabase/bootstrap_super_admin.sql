-- =====================================================================
-- Bootstrap a SUPER_ADMIN (platform operator).  One-time, manual.
--
-- A SUPER_ADMIN is cross-tenant: it manages the tenants (accounting firms)
-- and the GLOBAL reference data (tax rates + treasury IBANs) at /admin/*.
-- Unlike a TENANT_ADMIN it has NO tenant and NO app_user row — the backend
-- authorizes /api/v1/admin/** purely on the JWT `role` claim, and the global
-- reference tables carry no RLS. So all it needs is a Supabase auth user
-- whose app_metadata says role = SUPER_ADMIN.
--
-- Run in the Supabase SQL editor as the `postgres` role.
-- =====================================================================

-- 1) Create the auth user first:
--    Dashboard -> Authentication -> Users -> "Add user" (set an email + password).
--    Copy the new user's UUID into the placeholder below.

-- 2) Mark them SUPER_ADMIN (the access-token hook lifts this into the JWT).
--    Note: NO tenant_id / company_id — a super admin is not scoped to a tenant.
update auth.users
   set raw_app_meta_data = coalesce(raw_app_meta_data, '{}'::jsonb)
       || jsonb_build_object('role', 'SUPER_ADMIN')
 where id = '<AUTH_USER_UUID>';

-- 3) (No tenant or app_user insert — a SUPER_ADMIN has neither.)

-- 4) Make sure the access-token hook is installed AND enabled, otherwise the
--    `role` claim never reaches the JWT and every secured request fails closed:
--      - Run supabase/access_token_hook.sql once (as postgres), then
--      - Dashboard -> Authentication -> Hooks -> "Customize Access Token (JWT) Claims"
--        -> select public.custom_access_token_hook -> Save.
--
-- After this, sign OUT and sign IN again (claims are baked at token issue time).
-- Decode the access token at jwt.io and confirm it contains  "role": "SUPER_ADMIN".
-- The "Tenant admin" and "Reference data" items then appear in the sidebar.
