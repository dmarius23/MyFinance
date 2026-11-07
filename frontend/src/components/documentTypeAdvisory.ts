/**
 * Which advisory, if any, the Files list shows next to a document's type picker.
 *
 * An unrecognised document is imported rather than refused, so "unclassified" is a normal intake state:
 * it gets an amber nudge to classify it, because picking a type is what triggers extraction. A type that
 * genuinely does not belong on this screen stays a red error.
 *
 * Kept as a standalone pure function so it is unit-testable without mounting the whole modal.
 */
export type TypeAdvisory = "needsType" | "wrongType" | null;

/** The types the Files (reconcile) screen is about. */
const ACCEPTED_HERE = ["BANK_STATEMENT", "INVOICE", "RECEIPT"];

export function typeAdvisory(type: string): TypeAdvisory {
  if (type === "UNCLASSIFIED") {
    return "needsType";
  }
  return ACCEPTED_HERE.includes(type) ? null : "wrongType";
}
