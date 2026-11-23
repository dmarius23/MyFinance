import { describe, expect, it, vi } from "vitest";

import {
  documentChangeQueryKeys,
  EXTRACTION_REFETCH_DELAYS_MS,
  invalidateDocumentQueries,
  refetchWhileExtracting,
} from "./documentQueries";

const COMPANY = "c-1";
const PERIOD = "2026-09-01";

/** Minimal stand-in for QueryClient — only invalidateQueries is used. */
function fakeQc() {
  const calls: unknown[][] = [];
  return {
    calls,
    client: {
      invalidateQueries: ({ queryKey }: { queryKey: unknown[] }) => {
        calls.push(queryKey);
        return Promise.resolve();
      },
    },
  };
}

describe("documentChangeQueryKeys", () => {
  it("includes the two keys whose omission caused the stale list", () => {
    // The reconcile workspace kept its own copy of this list and left both of these out, so an upload
    // there did not show up until a manual browser refresh. This is the regression guard.
    const keys = documentChangeQueryKeys(COMPANY, PERIOD).map((k) => k.join("|"));
    expect(keys).toContain(["documents", COMPANY, PERIOD].join("|"));
    expect(keys).toContain(["invoices", COMPANY, PERIOD].join("|"));
  });

  it("covers the reconciliation and summary views a new document changes", () => {
    const first = documentChangeQueryKeys(COMPANY, PERIOD).map((k) => String(k[0]));
    for (const name of ["open-invoices", "doc-status", "doc-summary", "bank-txns",
                        "bank-files", "bank-stmts", "match-suggestions", "recon-summary"]) {
      expect(first).toContain(name);
    }
  });

  it("scopes every key to the company and period, except the month-wide roll-ups", () => {
    for (const key of documentChangeQueryKeys(COMPANY, PERIOD)) {
      expect(key).toContain(PERIOD);
      // doc-summary / recon-summary are month-wide across companies by design.
      if (!["doc-summary", "recon-summary"].includes(String(key[0]))) {
        expect(key).toContain(COMPANY);
      }
    }
  });
});

describe("invalidateDocumentQueries", () => {
  it("invalidates every key exactly once", () => {
    const { calls, client } = fakeQc();
    invalidateDocumentQueries(client as never, COMPANY, PERIOD);
    expect(calls).toHaveLength(documentChangeQueryKeys(COMPANY, PERIOD).length);
  });
});

describe("refetchWhileExtracting", () => {
  it("re-invalidates on each delay, because extraction lands after the upload response", () => {
    const { calls, client } = fakeQc();
    const scheduled: number[] = [];
    const fakeTimeout = ((fn: () => void, ms?: number) => {
      scheduled.push(ms ?? 0);
      fn(); // run immediately so the effect is observable
      return 0 as unknown as ReturnType<typeof setTimeout>;
    }) as unknown as typeof setTimeout;

    refetchWhileExtracting(client as never, COMPANY, PERIOD, fakeTimeout);

    expect(scheduled).toEqual(EXTRACTION_REFETCH_DELAYS_MS);
    // One full invalidation pass per scheduled retry.
    expect(calls).toHaveLength(
      EXTRACTION_REFETCH_DELAYS_MS.length * documentChangeQueryKeys(COMPANY, PERIOD).length);
  });

  it("spreads the retries over several seconds and stops", () => {
    expect(EXTRACTION_REFETCH_DELAYS_MS.length).toBeGreaterThan(1);
    expect(Math.max(...EXTRACTION_REFETCH_DELAYS_MS)).toBeGreaterThanOrEqual(5000);
    // Bounded on purpose — no open-ended polling.
    expect(Math.max(...EXTRACTION_REFETCH_DELAYS_MS)).toBeLessThanOrEqual(15000);
  });

  it("uses real setTimeout by default without throwing", () => {
    vi.useFakeTimers();
    const { client } = fakeQc();
    expect(() => refetchWhileExtracting(client as never, COMPANY, PERIOD)).not.toThrow();
    vi.clearAllTimers();
    vi.useRealTimers();
  });
});
