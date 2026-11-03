ALTER TABLE tenant_whatsapp_template
    ADD COLUMN source_kind text;

UPDATE tenant_whatsapp_template
SET source_kind = CASE
    WHEN kind = 'TAX' THEN 'TAX'
    WHEN kind = 'PAYROLL' THEN 'PAYROLL'
    WHEN kind = 'REPORT' THEN 'REPORT'
    ELSE 'REMINDER_DOCS_MISSING'
END
WHERE source_kind IS NULL;

ALTER TABLE tenant_whatsapp_template
    ALTER COLUMN source_kind SET NOT NULL;

ALTER TABLE tenant_whatsapp_template
    DROP CONSTRAINT uq_tenant_whatsapp_template;

ALTER TABLE tenant_whatsapp_template
    ADD CONSTRAINT uq_tenant_whatsapp_template
        UNIQUE (tenant_id, kind, source_kind, language);

CREATE INDEX idx_tenant_whatsapp_template_source_lookup
    ON tenant_whatsapp_template (tenant_id, kind, source_kind, language, status);
