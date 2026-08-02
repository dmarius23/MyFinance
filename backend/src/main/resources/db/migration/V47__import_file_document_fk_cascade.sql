-- Deleting a document was blocked by the ingestion ledger's FK (import_file.document_id had no
-- ON DELETE action → RESTRICT), so any Drive-synced document could not be removed:
--   "update or delete on table \"document\" violates foreign key constraint import_file_document_id_fkey".
-- Make the ledger row follow the document: on delete, drop the import_file row so a later sync
-- re-imports the file from Drive (enables delete-then-resync testing, and keeps the ledger honest —
-- a row claiming IMPORTED must point at a live document).
ALTER TABLE import_file DROP CONSTRAINT import_file_document_id_fkey;
ALTER TABLE import_file
    ADD CONSTRAINT import_file_document_id_fkey
    FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE;
