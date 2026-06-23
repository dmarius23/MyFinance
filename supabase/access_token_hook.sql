-- =====================================================================
-- Supabase Custom Access Token Hook for MyFinance
-- ---------------------------------------------------------------------
-- Lifts the user's app_metadata {tenant_id, role, company_id} into the
-- top-level JWT claims. The Spring backend (TenantContextFilter) reads
-- these claims to bind the tenant, role and (for representatives) company,
-- which in turn drive PostgreSQL row-level security.
--
-- Run this once in your project's SQL editor (as the `postgres` role),
-- then ENABLE it in:
--   Dashboard → Authentication → Hooks → "Customize Access Token (JWT) Claims"
--   → select public.custom_access_token_hook
-- (For local Supabase CLI, set it in supabase/config.toml — see docs/supabase-setup.md.)
-- =====================================================================

create or replace function public.custom_access_token_hook(event jsonb)
returns jsonb
language plpgsql
stable
as $$
declare
  claims jsonb;
  meta   jsonb;
begin
  select coalesce(raw_app_meta_data, '{}'::jsonb)
    into meta
    from auth.users
   where id = (event->>'user_id')::uuid;

  claims := coalesce(event->'claims', '{}'::jsonb);

  -- Only inject claims that are present in app_metadata.
  if meta ? 'tenant_id'  then claims := jsonb_set(claims, '{tenant_id}',  meta->'tenant_id');  end if;
  if meta ? 'role'       then claims := jsonb_set(claims, '{role}',       meta->'role');       end if;
  if meta ? 'company_id' then claims := jsonb_set(claims, '{company_id}', meta->'company_id'); end if;

  return jsonb_set(event, '{claims}', claims);
end;
$$;

-- The Auth server executes the hook as the `supabase_auth_admin` role.
grant usage  on schema public                              to supabase_auth_admin;
grant execute on function public.custom_access_token_hook(jsonb) to supabase_auth_admin;
grant select on table auth.users                           to supabase_auth_admin;

-- Never expose the hook to client roles.
revoke execute on function public.custom_access_token_hook(jsonb) from authenticated, anon, public;
