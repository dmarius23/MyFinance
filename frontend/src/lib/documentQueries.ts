import type { QueryClient } from "@tanstack/react-query";

/**
 * Every query key whose data can change when a document is added, removed or re-typed.
 *
 * This exists because the list was hand-maintained in two places and drifted: the reconcile workspace
 * omitted "documents" and "invoices", so uploading from that screen left the files list serving a stale
 * cache and the new document only appeared after a hard browser refresh. One definition, both callers.
 *
 * Add new document-derived queries here rather than in a component, or the drift comes back.
 */
export function documentChangeQueryKeys(companyId: string, period: string): unknown[][] {
  return [
    // The documents themselves, and the invoices extracted from them.
    ["documents", companyId, period],
    ["invoices", companyId, period],
    ["open-invoices", companyId, period],
    // Per-document status chips (duplicate / wrong party / date) and the month roll-ups.
    ["doc-status", companyId, period],
    ["doc-summary", period],
    // Reconciliation: a new invoice or statement changes transactions, suggestions and the summary.
    ["bank-txns", companyId, period],
    ["bank-files", companyId, period],
    ["bank-stmts", companyId, period],
    ["match-suggestions", companyId, period],
    ["recon-summary", period],
  ];
}

/** Invalidate everything a document change can affect. */
export function invalidateDocumentQueries(qc: QueryClient, companyId: string, period: string): void {
  for (const queryKey of documentChangeQueryKeys(companyId, period)) {
    void qc.invalidateQueries({ queryKey });
  }
}

/**
 * Extraction runs off the request thread and after the upload commits, so the invoice fields (supplier,
 * amount, date) do not exist yet when the upload response returns — invalidating once shows the document
 * with its data still blank. Re-invalidate a few times so the fields fill in on their own.
 *
 * A short bounded poll on purpose: extraction is spread over four independent @Async listeners, so there
 * is no single "extraction finished" moment the backend could report, and DocumentStatus has exactly one
 * value today. Inventing a lifecycle just to drive a spinner is a bigger change than this warrants.
 */
export const EXTRACTION_REFETCH_DELAYS_MS = [1500, 3500, 7000];

export function refetchWhileExtracting(qc: QueryClient, companyId: string, period: string,
                                       setTimeoutFn: typeof setTimeout = setTimeout): void {
  for (const delay of EXTRACTION_REFETCH_DELAYS_MS) {
    setTimeoutFn(() => invalidateDocumentQueries(qc, companyId, period), delay);
  }
}
