-- =====================================================================
-- DIAGNOSTIC — find documents imported from Google Drive under the WRONG
-- MONTH, because the folder path's period was mis-read.
--
-- WHY THIS MATTERS: the period decides which month a document belongs to.
-- A document filed a month (or a year) out reconciles against the wrong
-- bank statement, lands in the wrong monthly report and feeds the wrong
-- state-payment email. Nothing errors and nothing is flagged — the only
-- symptom is an accountant noticing a document in the wrong month.
--
-- THE TWO BUGS (both fixed in FolderMapper by PR #150, 2026-11-08):
--
--   1. A month folder carrying its own year was not recognised at all
--      when the month name was long. Matching was a substring test over
--      the segment with its spaces stripped, bounded by a 12-character
--      guard (meant to stop a month word inside a company name from
--      counting). "Septembrie 2026" -> SEPTEMBRIE2026 is 14 characters,
--      so it was skipped and NO month was found; the period then fell
--      back to the file's MODIFIED month — i.e. the upload date.
--      Broken for exactly the five long names, correct for the other
--      seven, which is why it went unnoticed for so long:
--          februarie  septembrie  octombrie  noiembrie  decembrie
--      Numbered folders ("9. Septembrie 2026") were NOT affected: the
--      leading ordinal was matched before the name path was reached. So
--      this hits clients whose folders are unnumbered.
--
--   2. The year and the month were harvested INDEPENDENTLY — the first
--      path segment containing a 20xx won the year, the first naming a
--      month won the month — and they were never checked for agreement.
--      So a month folder stating its own year was paired with an
--      ancestor's: "Arhiva 2026/Ianuarie 2027" resolved to 2026-01.
--      Bites once several years sit side by side, which is the norm.
--
-- WHAT THIS SCRIPT DOES: re-derives the period from each row's recorded
-- `source_path` using the SAME atomic rule the fixed FolderMapper now
-- uses — a single path segment naming BOTH a month and a year, deepest
-- segment first — and lists every row where that disagrees with the
-- period actually stored.
--
-- A MISMATCH IS NOT AUTOMATICALLY A BUG. Read "Interpreting" below before
-- repairing anything: for invoices and bank statements the importer could
-- also take the period from the DOCUMENT's own content, which legitimately
-- overrides the folder.
--
-- RUN IT: Supabase SQL editor (production), as `postgres`
--   (`rolbypassrls = true`, so RLS won't hide other tenants' rows).
--   STEPS 1-3 ARE READ-ONLY. Step 4 is commented out.
--
-- VERIFIED on Postgres 16 against fixture rows reproducing the real paths,
-- including the false positives this has to avoid: "MAI CONSTRUCT SRL 2026"
-- (a month word AND a year inside a company folder) and "MAIN 2026" are both
-- correctly ignored, while "Septembrie 2026" and "Decembrie 2026" filed under
-- the upload month are both caught. The leftover-letters test in STEP 1(b) is
-- what rejects the company names — do not simplify it away.
-- =====================================================================


-- ---------------------------------------------------------------------
-- STEP 1 — SCALE. Read-only. One row. Run this first: it answers
-- "is this a handful of documents or thousands?", which decides whether
-- the repair is a per-client re-sync or a migration.
-- ---------------------------------------------------------------------
with ro_month(name, mon) as (
  values ('ianuarie',1),('februarie',2),('martie',3),('aprilie',4),('mai',5),('iunie',6),
         ('iulie',7),('august',8),('septembrie',9),('octombrie',10),('noiembrie',11),('decembrie',12)
),
seg as (
  select f.id, f.tenant_id, f.company_id, f.document_id, f.filename,
         f.source_path, f.period_month as stored_period, f.status,
         s.segment, s.ord
    from public.import_file f
    cross join lateral unnest(string_to_array(f.source_path, '/'))
         with ordinality as s(segment, ord)
   where f.source_path is not null
     and f.status = 'IMPORTED'
     and f.document_id is not null
),
-- Segments naming BOTH a month and a year. Mirrors the atomic rule in
-- FolderMapper.completePeriodOf: a period must come from ONE segment, so
-- an ancestor folder's year can never be paired with a deeper month.
stated as (
  -- a) "2026-05", "2026_03", "202605" — anchored, so a longer number cannot match
  select seg.id, seg.ord, make_date((m[1])::int, (m[2])::int, 1) as path_period
    from seg,
         lateral (select regexp_match(seg.segment, '^\s*(20[0-9]{2})[-_. ]?([0-9]{1,2})\s*$') as m) r
   where r.m is not null
     and (m[2])::int between 1 and 12
  union all
  -- b) "Septembrie 2026", "9. Septembrie 2026" — the month name as a WHOLE
  --    word, with nothing left over besides its year and an optional ordinal.
  --    That leftover test is what keeps "MAI CONSTRUCT SRL" from counting as May.
  select seg.id, seg.ord, make_date((y[1])::int, rm.mon, 1)
    from seg
    join ro_month rm
      on lower(seg.segment) ~ ('(^|[^a-z])' || rm.name || '([^a-z]|$)')
    cross join lateral (select regexp_match(seg.segment, '(20[0-9]{2})') as y) yy
   where yy.y is not null
     and regexp_replace(regexp_replace(lower(seg.segment), rm.name, '', 'g'),
                        '[^a-z]', '', 'g') = ''
),
-- Deepest segment wins: the month folder sits closest to the file.
deepest as (
  select distinct on (st.id) st.id, st.path_period
    from stated st
   order by st.id, st.ord desc
)
select count(*)                                                        as rows_with_a_period_in_the_path,
       count(*) filter (where d.path_period <> s.stored_period)         as mismatched,
       count(distinct s.company_id) filter (where d.path_period <> s.stored_period)
                                                                        as companies_affected,
       count(distinct s.tenant_id) filter (where d.path_period <> s.stored_period)
                                                                        as tenants_affected,
       min(s.stored_period) filter (where d.path_period <> s.stored_period)
                                                                        as earliest_stored_period,
       max(s.stored_period) filter (where d.path_period <> s.stored_period)
                                                                        as latest_stored_period
  from (select distinct id, tenant_id, company_id, stored_period from seg) s
  join deepest d on d.id = s.id;


-- ---------------------------------------------------------------------
-- STEP 2 — DETAIL. Read-only. Every mismatched row, worst first.
--
-- `likely_cause` separates the two bugs, because they need different
-- judgement:
--   LENGTH_GUARD  the path names a long month, unnumbered, and the stored
--                 period looks like an upload date (later than the path).
--                 Highest confidence — the period was never read at all.
--   YEAR_MISMATCH the month agrees but the YEAR does not: the year came
--                 from an ancestor folder. Also high confidence.
--   OTHER         anything else — inspect before trusting it.
-- ---------------------------------------------------------------------
with ro_month(name, mon) as (
  values ('ianuarie',1),('februarie',2),('martie',3),('aprilie',4),('mai',5),('iunie',6),
         ('iulie',7),('august',8),('septembrie',9),('octombrie',10),('noiembrie',11),('decembrie',12)
),
seg as (
  select f.id, f.tenant_id, f.company_id, f.document_id, f.filename,
         f.source_path, f.period_month as stored_period, f.status,
         s.segment, s.ord
    from public.import_file f
    cross join lateral unnest(string_to_array(f.source_path, '/'))
         with ordinality as s(segment, ord)
   where f.source_path is not null
     and f.status = 'IMPORTED'
     and f.document_id is not null
),
stated as (
  select seg.id, seg.ord, seg.segment, make_date((m[1])::int, (m[2])::int, 1) as path_period
    from seg,
         lateral (select regexp_match(seg.segment, '^\s*(20[0-9]{2})[-_. ]?([0-9]{1,2})\s*$') as m) r
   where r.m is not null
     and (m[2])::int between 1 and 12
  union all
  select seg.id, seg.ord, seg.segment, make_date((y[1])::int, rm.mon, 1)
    from seg
    join ro_month rm
      on lower(seg.segment) ~ ('(^|[^a-z])' || rm.name || '([^a-z]|$)')
    cross join lateral (select regexp_match(seg.segment, '(20[0-9]{2})') as y) yy
   where yy.y is not null
     and regexp_replace(regexp_replace(lower(seg.segment), rm.name, '', 'g'),
                        '[^a-z]', '', 'g') = ''
),
deepest as (
  select distinct on (st.id) st.id, st.path_period, st.segment as month_folder
    from stated st
   order by st.id, st.ord desc
),
rows_ as (
  select distinct f.id, f.tenant_id, f.company_id, f.document_id,
         f.filename, f.source_path, f.stored_period
    from seg f
)
select c.legal_name                                        as company,
       r.stored_period                                     as stored,
       d.path_period                                       as should_be,
       d.month_folder,
       doc.type                                            as doc_type,
       r.filename,
       case
         when d.path_period <> r.stored_period
          and date_part('year', d.path_period) = date_part('year', r.stored_period)
          and d.month_folder !~ '^\s*[0-9]'
          and lower(d.month_folder) ~ '(februarie|septembrie|octombrie|noiembrie|decembrie)'
           then 'LENGTH_GUARD'
         when date_part('year', d.path_period) <> date_part('year', r.stored_period)
          and date_part('month', d.path_period) = date_part('month', r.stored_period)
           then 'YEAR_MISMATCH'
         else 'OTHER'
       end                                                 as likely_cause,
       r.source_path,
       r.document_id
  from rows_ r
  join deepest d  on d.id = r.id
  left join public.company  c   on c.id = r.company_id
  left join public.document doc on doc.id = r.document_id
 where d.path_period <> r.stored_period
 order by likely_cause, company, r.stored_period desc, r.filename;


-- ---------------------------------------------------------------------
-- STEP 3 — REPAIR PLANNING. Read-only. Mismatches grouped by company and
-- by the month they SHOULD be in, which is the unit a re-sync works in.
-- ---------------------------------------------------------------------
with ro_month(name, mon) as (
  values ('ianuarie',1),('februarie',2),('martie',3),('aprilie',4),('mai',5),('iunie',6),
         ('iulie',7),('august',8),('septembrie',9),('octombrie',10),('noiembrie',11),('decembrie',12)
),
seg as (
  select f.id, f.tenant_id, f.company_id, f.document_id,
         f.source_path, f.period_month as stored_period,
         s.segment, s.ord
    from public.import_file f
    cross join lateral unnest(string_to_array(f.source_path, '/'))
         with ordinality as s(segment, ord)
   where f.source_path is not null
     and f.status = 'IMPORTED'
     and f.document_id is not null
),
stated as (
  select seg.id, seg.ord, make_date((m[1])::int, (m[2])::int, 1) as path_period
    from seg,
         lateral (select regexp_match(seg.segment, '^\s*(20[0-9]{2})[-_. ]?([0-9]{1,2})\s*$') as m) r
   where r.m is not null and (m[2])::int between 1 and 12
  union all
  select seg.id, seg.ord, make_date((y[1])::int, rm.mon, 1)
    from seg
    join ro_month rm
      on lower(seg.segment) ~ ('(^|[^a-z])' || rm.name || '([^a-z]|$)')
    cross join lateral (select regexp_match(seg.segment, '(20[0-9]{2})') as y) yy
   where yy.y is not null
     and regexp_replace(regexp_replace(lower(seg.segment), rm.name, '', 'g'),
                        '[^a-z]', '', 'g') = ''
),
deepest as (
  select distinct on (st.id) st.id, st.path_period
    from stated st order by st.id, st.ord desc
),
rows_ as (
  select distinct id, tenant_id, company_id, stored_period from seg
)
select c.legal_name            as company,
       d.path_period           as should_be,
       count(*)                as documents,
       array_agg(distinct r.stored_period order by r.stored_period) as currently_filed_under
  from rows_ r
  join deepest d on d.id = r.id
  left join public.company c on c.id = r.company_id
 where d.path_period <> r.stored_period
 group by c.legal_name, d.path_period
 order by documents desc, company, should_be;


-- ---------------------------------------------------------------------
-- Interpreting the result — READ BEFORE REPAIRING
--
-- A mismatch means "the folder path and the stored period disagree". That
-- is not always the folder's fault:
--
--   * For INVOICE and BANK_STATEMENT the importer may take the period from
--     the document's own CONTENT (DocumentValidator.contentPeriod), which
--     legitimately overrides the folder — an invoice dated 30 September
--     filed by the client in the October folder is correctly September.
--     So prefer `doc_type` = RECEIPT / UNCLASSIFIED and the LENGTH_GUARD
--     and YEAR_MISMATCH causes when deciding what to trust.
--   * A client may genuinely file a document under a different month than
--     its date (a December invoice booked in January). The folder is then
--     the client's intent and the stored period may be the better value.
--
-- Treat STEP 2 as a worklist to confirm, not as a set of rows to rewrite.
--
-- WHY NOT JUST RE-SYNC: a row already `IMPORTED` with an unchanged etag is
-- skipped on later syncs, so re-syncing alone changes nothing. Deleting the
-- document and re-syncing re-imports it with the corrected period (and the
-- corrected type, from PR #148/#149). That is the safest repair because it
-- re-runs the real pipeline instead of hand-editing a date.
--
-- `reclassify` does NOT help here — it re-reads a document's TYPE, never
-- its period.
-- ---------------------------------------------------------------------


-- ---------------------------------------------------------------------
-- STEP 4 — REPAIR (commented out on purpose).
--
-- Option A (PREFERRED) — delete + re-sync, per company and month. Runs the
-- real importer, so period, type, validation and extraction are all redone
-- consistently. Do it from the app: delete the affected documents, then
-- sync that company/month. No SQL.
--
-- Option B — correct the period in place. ONLY for rows confirmed in STEP 2,
-- and ONLY where you are sure the folder is right. This moves the document
-- but does NOT re-run reconciliation, reports or the state-payment figures
-- for either month, so both the old and the new month must be reviewed
-- afterwards. One company and one target month at a time; never a blanket
-- UPDATE over the whole mismatch set.
-- ---------------------------------------------------------------------
-- begin;
--
-- -- Inspect exactly what will change, FIRST:
-- select d.id, d.original_filename, d.type, d.period_month as from_month
--   from public.document d
--  where d.id in ('<document_id from STEP 2>' /*, … */);
--
-- update public.document
--    set period_month = date '2026-09-01'          -- <<< the `should_be` value
--  where id in ('<document_id from STEP 2>' /*, … */);
--
-- -- Keep the import ledger consistent with the document it points at,
-- -- otherwise this audit keeps reporting the same rows as mismatched.
-- update public.import_file
--    set period_month = date '2026-09-01'          -- <<< the same value
--  where document_id in ('<document_id from STEP 2>' /*, … */);
--
-- -- Verify BEFORE committing: expect every row on the new month.
-- select id, original_filename, type, period_month
--   from public.document
--  where id in ('<document_id from STEP 2>' /*, … */);
--
-- commit;     -- or: rollback;  if that does not look right
--
-- THEN re-run STEP 1. The mismatch count should drop by exactly the number
-- of rows you touched. Afterwards re-check reconciliation for BOTH the old
-- and the new month — moving a document changes what each month contains.
-- ---------------------------------------------------------------------


-- ---------------------------------------------------------------------
-- Related, deliberately NOT covered by this script
--
--   * Rows with status NEEDS_REVIEW were never imported, so they have no
--     document and no period to be wrong. They are re-evaluated on every
--     sync, so the fixed code picks them up by itself. To see them:
--       select filename, detail, source_path from public.import_file
--        where status = 'NEEDS_REVIEW' order by created_at desc;
--
--   * Documents whose path names NO period at all are out of scope: they
--     inherited the file's upload month both before and after the fix, so
--     there is nothing to compare against. DocumentValidator.contentPeriod
--     gives up on RECEIPT and UNCLASSIFIED, which is the open gap there.
--
--   * Documents uploaded by hand (source REP / EMPLOYEE) or by email have
--     no Drive path, so they are not in `import_file` and never appear here.
-- ---------------------------------------------------------------------


-- ---------------------------------------------------------------------
-- Audited so far
--   (append: date, who ran it, STEP 1 counts, what was repaired and how)
-- ---------------------------------------------------------------------
