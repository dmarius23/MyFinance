/** Entity types for a Romanian company. */
export const ENTITY_TYPES = ["SRL", "SA", "PFA", "ONG"] as const;
export type EntityType = (typeof ENTITY_TYPES)[number];

/** VAT period codes stored in the DB. Labels via i18n key `vatPeriod.<code>`. */
export const VAT_PERIODS = ["MONTHLY", "QUARTERLY", "SEMIANNUAL", "ANNUAL"] as const;
export type VatPeriod = (typeof VAT_PERIODS)[number];

export const vatPeriodKey = (code: string) => `vatPeriod.${code}`;

/** HTML5 `pattern` for a Romanian fiscal code (CUI/CIF): 2–10 digits, optional RO prefix for VAT payers. */
export const CUI_PATTERN = "([Rr][Oo])?[0-9]{2,10}";
/** HTML5 `pattern` for a Romanian phone: 0 + 9 digits, or +40 / 0040 + 9 digits. Optional field. */
export const PHONE_PATTERN = "(\\+40|0040|0)[0-9]{9}";
