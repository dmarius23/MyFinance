/** Entity types for a Romanian company. */
export const ENTITY_TYPES = ["SRL", "SA", "PFA", "ONG"] as const;
export type EntityType = (typeof ENTITY_TYPES)[number];

/** VAT period codes stored in the DB. Labels via i18n key `vatPeriod.<code>`. */
export const VAT_PERIODS = ["MONTHLY", "QUARTERLY", "SEMIANNUAL", "ANNUAL"] as const;
export type VatPeriod = (typeof VAT_PERIODS)[number];

export const vatPeriodKey = (code: string) => `vatPeriod.${code}`;
