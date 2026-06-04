// Canonical VAT-status values stored on a company (vat_status). Labels are resolved via i18n keys
// `vatStatus.<value>` (RO/EN). Unknown/legacy values fall back to the raw string at the call site.
export const VAT_STATUSES = ["VAT_PAYER", "NON_VAT_PAYER"] as const;

export type VatStatus = (typeof VAT_STATUSES)[number];

/** i18n key for a VAT-status value, e.g. "VAT_PAYER" -> "vatStatus.VAT_PAYER". */
export const vatStatusKey = (value: string) => `vatStatus.${value}`;
