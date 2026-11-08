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

/** How long after upload a document is still badged as new. */
export const RECENT_UPLOAD_WINDOW_MS = 2 * 60 * 1000;

/**
 * Was this document uploaded just now? Used to badge it in the list so a freshly added file is obvious
 * without the accountant hunting for it.
 *
 * Derived from the document's own `uploadedAt` rather than from ids remembered after an upload: the
 * upload can happen on the reconcile screen while the list lives in the modal, and a timestamp needs no
 * state shared between them — it also survives a page reload. Returns false for an unparseable date.
 */
export function isRecentUpload(uploadedAt: string | null | undefined, nowMs: number): boolean {
  if (!uploadedAt) {
    return false;
  }
  const t = Date.parse(uploadedAt);
  if (Number.isNaN(t)) {
    return false;
  }
  const age = nowMs - t;
  // Guard the future case too: a clock skew between server and browser must not badge everything.
  return age >= 0 && age <= RECENT_UPLOAD_WINDOW_MS;
}

/** The types the Files (reconcile) screen is about. */
const ACCEPTED_HERE = ["BANK_STATEMENT", "INVOICE", "RECEIPT"];

export function typeAdvisory(type: string): TypeAdvisory {
  if (type === "UNCLASSIFIED") {
    return "needsType";
  }
  return ACCEPTED_HERE.includes(type) ? null : "wrongType";
}
