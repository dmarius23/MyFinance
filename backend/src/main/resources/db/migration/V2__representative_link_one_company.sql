-- FR-011: a representative is linked to exactly one company. Move the primary key from
-- (user_id, company_id) to user_id so a rep can have at most one company link.
ALTER TABLE representative_link DROP CONSTRAINT representative_link_pkey;
ALTER TABLE representative_link ADD PRIMARY KEY (user_id);
