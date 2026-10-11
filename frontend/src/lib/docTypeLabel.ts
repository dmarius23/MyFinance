type T = (key: string) => string;

/** Backend DocumentType → i18n key. Kept in one place so every screen labels a type the same way. */
const KEYS: Record<string, string> = {
  BANK_STATEMENT: "portal.docType.bank",
  INVOICE: "portal.docType.invoice",
  RECEIPT: "portal.docType.receipt",
  TRIAL_BALANCE: "portal.docType.balance",
  DECLARATION: "portal.docType.declaration",
  PAYROLL: "portal.docType.payroll",
  UNCLASSIFIED: "portal.docType.other",
};

/** Human label for a backend document type; falls back to the raw name for an unknown type. */
export function docTypeLabel(t: T, type: string): string {
  const key = KEYS[type];
  return key ? t(key) : type;
}

/** "Extras de cont, Bon / chitanță" — the distinct types of a flagged set, for a chip or tooltip. */
export function docTypeLabels(t: T, types: readonly string[]): string {
  return types.map((ty) => docTypeLabel(t, ty)).join(", ");
}
