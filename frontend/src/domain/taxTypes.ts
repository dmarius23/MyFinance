/** Canonical tax-type codes for county treasury accounts. Labels via i18n key `taxType.<code>`. */
export const TAX_TYPES = [
  "TVA",
  "IMPOZIT_PROFIT",
  "IMPOZIT_MICRO",
  "IMPOZIT_SALARII",
  "CAS",
  "CASS",
  "CAM",
  "IMPOZIT_DIVIDENDE",
] as const;

export type TaxType = (typeof TAX_TYPES)[number];

export const taxTypeKey = (code: string) => `taxType.${code}`;
