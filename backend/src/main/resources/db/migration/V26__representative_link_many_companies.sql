-- A representative can now be assigned to MULTIPLE companies (reverses V2's one-company rule).
-- Move to a surrogate primary key + a unique (user_id, company_id) so the same user can link to many
-- companies. Representative requests are still constrained server-side to a company the user is linked to.
ALTER TABLE representative_link DROP CONSTRAINT representative_link_pkey;
ALTER TABLE representative_link ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE representative_link ADD PRIMARY KEY (id);
ALTER TABLE representative_link ADD CONSTRAINT uq_rep_link_user_company UNIQUE (user_id, company_id);
CREATE INDEX idx_rep_link_user ON representative_link(user_id);
