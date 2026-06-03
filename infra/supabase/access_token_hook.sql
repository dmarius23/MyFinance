-- Supabase custom access-token hook: lifts app_metadata.{tenant_id, role, company_id} into
-- top-level JWT claims that the backend reads (SupabaseJwtAuthoritiesConverter / TenantContextFilter).
-- Apply in the Supabase project (SQL editor), then enable under Authentication -> Hooks ->
-- Customize Access Token, pointing at public.custom_access_token_hook.
create or replace function public.custom_access_token_hook(event jsonb)
returns jsonb
language plpgsql
stable
as $$
declare
  claims    jsonb;
  meta      jsonb;
begin
  claims := event->'claims';
  meta   := coalesce(claims->'app_metadata', '{}'::jsonb);

  if meta ? 'tenant_id' then
    claims := jsonb_set(claims, '{tenant_id}', meta->'tenant_id');
  end if;
  if meta ? 'role' then
    claims := jsonb_set(claims, '{role}', meta->'role');
  end if;
  if meta ? 'company_id' then
    claims := jsonb_set(claims, '{company_id}', meta->'company_id');
  end if;

  event := jsonb_set(event, '{claims}', claims);
  return event;
end;
$$;

grant usage on schema public to supabase_auth_admin;
grant execute on function public.custom_access_token_hook to supabase_auth_admin;
revoke execute on function public.custom_access_token_hook from authenticated, anon, public;
