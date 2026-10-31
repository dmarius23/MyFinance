-- =====================================================================
-- ONE-OFF BACKFILL — split the old catch-all `INVITED` into
-- `PENDING_INVITE` (provisioned but never emailed) vs `INVITED` (link sent).
--
-- WHY THIS IS NOT A FLYWAY MIGRATION: the only trustworthy evidence of
-- "were they ever emailed / did they ever sign in" lives in `auth.users`
-- (GoTrue), not in our own tables. The `auth` schema does not exist in the
-- Testcontainers/local Postgres used by ITs, so a migration referencing it
-- would break every integration test and local boot.
--
-- RUN IT: Supabase SQL editor (production), as `postgres`.
--   PRODUCTION ONLY — it joins `public.app_user` to `auth.users`, which only
--   share one database in prod. In local dev auth lives in Supabase cloud
--   while app data is in local Postgres, so the join finds nothing there.
--
-- Run STEP 1 first and sanity-check the counts against what you know about
-- your own history (how many companies came from the CSV import) BEFORE
-- running STEP 2.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Which signals are trustworthy, and which are not
-- ---------------------------------------------------------------------
--   last_sign_in_at   RELIABLE. Set by GoTrue on a real sign-in. Non-null
--                     means this person has actually used the portal.
--   recovery_sent_at  Evidence that a "set your password" link was minted.
--                     Our app only ever emails a rep via admin generate_link
--                     (type=recovery), so this is our best proxy for
--                     "we contacted them".
--   email_confirmed_at  USELESS HERE — `provision()` creates every user with
--                     email_confirm=true, so it is set for imported reps too.
--                     Do not reach for it.
--
-- CAVEAT on recovery_sent_at: admin generate_link does not send the mail
-- itself (we send our own branded message), and it is not contractually
-- guaranteed to stamp recovery_sent_at. If it turns out it does NOT, then
-- reps who were emailed in-app but never clicked will also be moved to
-- PENDING_INVITE. That error is cheap and self-correcting: the accountant
-- sends one redundant invite. The opposite error — leaving a never-contacted
-- rep looking "Invitat" — is the bug we are fixing. STEP 1 lets you see the
-- size of each bucket before committing to anything.

-- ---------------------------------------------------------------------
-- STEP 1 — DRY RUN. Changes nothing. Read the counts.
-- ---------------------------------------------------------------------
select case
         when a.last_sign_in_at is not null              then 'A: has signed in        -> ACTIVE'
         when a.recovery_sent_at is null                 then 'B: never emailed        -> PENDING_INVITE'
         else                                                 'C: emailed, not signed in -> leave INVITED'
       end                        as bucket,
       count(*)                   as reps,
       min(u.created_at)::date    as earliest,
       max(u.created_at)::date    as latest
  from public.app_user u
  join auth.users a on a.id = u.id
 where u.role   = 'REPRESENTATIVE'
   and u.status = 'INVITED'            -- never touch ACTIVE or INACTIVE
 group by 1
 order by 1;

-- Optional: eyeball the actual rows in bucket B before flipping them.
-- select u.email, u.name, u.created_at
--   from public.app_user u join auth.users a on a.id = u.id
--  where u.role = 'REPRESENTATIVE' and u.status = 'INVITED'
--    and a.last_sign_in_at is null and a.recovery_sent_at is null
--  order by u.created_at;

-- ---------------------------------------------------------------------
-- STEP 2 — THE BACKFILL. Wrapped in a transaction: check the row counts
-- it reports, then COMMIT (or ROLLBACK if they look wrong).
-- ---------------------------------------------------------------------
begin;

-- 2a. Never emailed, never signed in -> they still need an invite.
update public.app_user u
   set status = 'PENDING_INVITE'
  from auth.users a
 where a.id     = u.id
   and u.role   = 'REPRESENTATIVE'
   and u.status = 'INVITED'
   and a.last_sign_in_at is null
   and a.recovery_sent_at is null;

-- 2b. Already signed in -> ACTIVE, and seed the last_login we never recorded.
--     Matches the new in-app behaviour (first authenticated request promotes
--     a rep to ACTIVE), so old rows stop lying about people already working.
update public.app_user u
   set status     = 'ACTIVE',
       last_login = a.last_sign_in_at
  from auth.users a
 where a.id     = u.id
   and u.role   = 'REPRESENTATIVE'
   and u.status = 'INVITED'
   and a.last_sign_in_at is not null;

-- Verify before committing: expect no REPRESENTATIVE left in a state that
-- contradicts auth.users.
select u.status, count(*)
  from public.app_user u join auth.users a on a.id = u.id
 where u.role = 'REPRESENTATIVE'
 group by 1 order by 1;

commit;
-- rollback;   -- use this instead if the numbers above look wrong

-- ---------------------------------------------------------------------
-- STEP 3 — OPTIONAL: the same ACTIVE alignment for STAFF (admins/employees).
-- Staff are never bulk-imported, so none of them belong in PENDING_INVITE —
-- but those who have signed in are still stuck on INVITED for the same
-- reason. Run only if you want the Team page to read correctly too.
-- ---------------------------------------------------------------------
-- begin;
-- update public.app_user u
--    set status = 'ACTIVE', last_login = a.last_sign_in_at
--   from auth.users a
--  where a.id = u.id
--    and u.role in ('TENANT_ADMIN', 'EMPLOYEE', 'SUPER_ADMIN')
--    and u.status = 'INVITED'
--    and a.last_sign_in_at is not null;
-- commit;
