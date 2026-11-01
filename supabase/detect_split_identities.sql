-- =====================================================================
-- DIAGNOSTIC — find users whose app_user.id has diverged from their
-- auth.users.id, and (STEP 3) repair one.
--
-- WHY THIS MATTERS: `app_user.id` IS the Supabase auth user id — the JWT
-- `sub`. Nothing in the database enforces that (no FK can cross into the
-- `auth` schema), so the two can silently diverge.
--
-- THE SYMPTOM IS NASTY because nothing errors. The user authenticates
-- normally and even gets correct claims (tenant_id/role come from
-- auth.users.app_metadata), but every `app_user.findById(jwt.sub)`
-- returns nothing. Observed consequences:
--   * absent from the Team page (listUsers reads app_user)
--   * EmailEnvelopeService.currentUserName() -> null, so emails they send
--     go out with no From-name
--   * their notifications are invisible (queried by JWT subject)
-- Auth logs look healthy, RLS looks healthy, no exception is thrown.
--
-- THE APP CANNOT SELF-HEAL THIS. Re-inviting hits `existsByEmail` ->
-- ConflictException, so the stale row blocks its own repair. SQL only.
--
-- RUN IT: Supabase SQL editor (production), as `postgres`
--   (`rolbypassrls = true`, so RLS won't hide rows). PRODUCTION ONLY —
--   it joins public.app_user to auth.users, which only share one database
--   in prod; locally auth lives in Supabase cloud and app data does not.
--
-- KNOWN CAUSE: unproven. `AuthUserCleanup` (outbox REQUIRES_NEW delete on
-- a failed persist) was reviewed and looks consistent — a rollback drops
-- the app_user row too, so both sides vanish together. The likely origin
-- is an auth user deleted by hand in the Supabase dashboard and later
-- recreated with a fresh id. If a case appears with nobody having touched
-- the dashboard, the mechanism is in application code — investigate.
-- =====================================================================

-- ---------------------------------------------------------------------
-- STEP 1 — DETECT. Read-only. Expect ZERO rows.
--
-- The FULL OUTER JOIN is the whole point: a split identity shows up as
-- TWO rows for one address (one with in_app_user=false, one with
-- in_auth_users=false). An inner join hides the condition completely.
-- ---------------------------------------------------------------------
select coalesce(u.email, a.email)                                     as email,
       u.id                                                           as app_user_id,
       a.id                                                           as auth_user_id,
       (u.id is not null)                                             as in_app_user,
       (a.id is not null)                                             as in_auth_users,
       u.role, u.status, u.tenant_id,
       a.raw_app_meta_data                                            as auth_claims,
       a.last_sign_in_at
  from public.app_user u
  full outer join auth.users a on a.id = u.id
 where u.id is null or a.id is null
 order by email;

-- Rows where in_app_user = false AND the same address also appears with
-- in_auth_users = false  ->  SPLIT IDENTITY (needs STEP 2 + STEP 3).
--
-- A row appearing only once is NOT necessarily broken:
--   * in_auth_users = true, no app_user twin  -> an auth user we never
--     provisioned (old/abandoned signup). Harmless; they have no tenant.
--   * in_app_user = true, no auth twin        -> app row whose auth user
--     is gone. Broken, but there is nothing to re-point TO: re-create or
--     delete it rather than running STEP 3.

-- ---------------------------------------------------------------------
-- STEP 2 — IMPACT. Read-only. Substitute the orphan app_user.id below.
--
-- DO NOT hand-build this list by grepping migrations. Doing that once
-- missed `created_by` (x3), `uploaded_by` and `sent_by` (x5). This scans
-- EVERY uuid column of EVERY public table for the orphan id, so new
-- columns are covered automatically.
-- ---------------------------------------------------------------------
select table_name, column_name, cnt
from (
  select c.table_name, c.column_name,
         (xpath('/row/cnt/text()',
            query_to_xml(format('select count(*) as cnt from public.%I where %I = %L',
                                c.table_name, c.column_name,
                                '00000000-0000-0000-0000-000000000000'),  -- <<< orphan app_user.id
                         false, true, '')))[1]::text::int as cnt
    from information_schema.columns c
    join information_schema.tables  t
      on t.table_schema = c.table_schema and t.table_name = c.table_name
   where c.table_schema = 'public'
     and c.data_type    = 'uuid'
     and t.table_type   = 'BASE TABLE'
) s
where cnt > 0
order by table_name, column_name;

-- Read the result against the two HARD foreign keys to app_user(id):
--   company.responsible_user_id   -- no ON DELETE  -> RESTRICT
--   representative_link.user_id   -- ON DELETE CASCADE
-- There is NO `ON UPDATE CASCADE` anywhere in the schema.
--
--   * Both at zero  -> a direct PK update is legal (STEP 3). Postgres only
--     checks a foreign key against rows that actually exist.
--   * Either non-zero -> do NOT update the PK in place. Instead, inside one
--     transaction: free the (tenant_id, email) unique key on the old row,
--     INSERT a copy carrying the real auth id, re-point every child listed
--     above, then DELETE the old row.
-- Everything else in the list (notification.recipient_user_id,
-- task.assignee_id, push_subscription.user_id, audit_entry.actor_id,
-- *.created_by, *.uploaded_by, *.sent_by) has no FK, so it will silently
-- dangle unless re-pointed.

-- ---------------------------------------------------------------------
-- STEP 3 — REPAIR (commented out on purpose). Only when STEP 2 shows no
-- hard-FK rows. Add one UPDATE per table STEP 2 listed.
--
-- No re-invite is needed when auth_claims from STEP 1 already carry
-- tenant_id + role — the auth side is fine; only our id was wrong.
-- ---------------------------------------------------------------------
-- begin;
--
-- update public.app_user
--    set id = '<real auth.users.id>'
--  where id = '<orphan app_user.id>';
--
-- -- repeat for EVERY table STEP 2 reported, e.g.:
-- update public.notification
--    set recipient_user_id = '<real auth.users.id>'
--  where recipient_user_id = '<orphan app_user.id>';
--
-- -- Verify BEFORE committing: expect exactly ONE row, in_auth_users = true.
-- select u.id, u.email, u.role, u.status,
--        exists (select 1 from auth.users a where a.id = u.id) as in_auth_users
--   from public.app_user u
--  where lower(u.email) = lower('<the address>');
--
-- commit;     -- or: rollback;  if that row does not look right
--
-- THEN RE-RUN STEP 1 IN A FRESH EDITOR RUN. An editor-held transaction can
-- look applied without having committed; a new run proves it persisted.
-- One row for the address, with both sides present, means repaired.

-- ---------------------------------------------------------------------
-- Repaired so far
--   2026-11-01  codrut.merlusca@gmail.com  tenant 8ee40839…  TENANT_ADMIN
--               app_user 64365ea8-7053-4e14-91ba-3e66bf24f28f
--                    ->  auth 1443ae6b-1fb8-4f80-9d6d-6481c3d72e52
--               Both hard FKs were zero; only notification
--               (40 rows) needed re-pointing. auth_claims were already
--               correct, so no re-invite was required.
-- ---------------------------------------------------------------------
