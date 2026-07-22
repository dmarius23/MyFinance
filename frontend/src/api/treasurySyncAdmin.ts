import { api } from "../lib/apiClient";

/**
 * SUPER_ADMIN ANAF treasury-IBAN sync: trigger a crawl of the ANAF catalogue, review the diff against the
 * live treasury reference data, then apply or cancel it. Mirrors the backend
 * {@code /api/v1/admin/treasury-sync} endpoints.
 */

export type SyncRunStatus = "RUNNING" | "READY_FOR_REVIEW" | "APPLIED" | "FAILED" | "CANCELLED";
export type SyncChange = "ADDED" | "CHANGED" | "UNCHANGED" | "ERROR";

export interface TreasurySyncRun {
  id: string;
  status: SyncRunStatus;
  effectiveFrom: string; // ISO date (yyyy-MM-dd) stamped on applied rows
  countiesTotal: number;
  treasuriesTotal: number;
  parsedOk: number;
  parseFailed: number;
  notes: string | null;
  startedAt: string; // ISO timestamp
  finishedAt: string | null;
  appliedAt: string | null;
}

export interface TreasurySyncItem {
  id: string;
  county: string | null;
  treasuryCode: string | null;
  residence: string | null;
  sourceUrl: string | null;
  iban5503: string | null;
  ibanCam: string | null;
  ibanTvaIntern: string | null;
  ibanTvaExtern: string | null;
  change: SyncChange;
  error: string | null;
}

export const treasurySyncApi = {
  start: (effectiveFrom?: string) =>
    api<TreasurySyncRun>("/api/v1/admin/treasury-sync", {
      method: "POST",
      body: JSON.stringify(effectiveFrom ? { effectiveFrom } : {}),
    }),
  list: () => api<TreasurySyncRun[]>("/api/v1/admin/treasury-sync"),
  get: (runId: string) => api<TreasurySyncRun>(`/api/v1/admin/treasury-sync/${runId}`),
  items: (runId: string, changes?: SyncChange[]) =>
    api<TreasurySyncItem[]>(
      `/api/v1/admin/treasury-sync/${runId}/items${changes && changes.length ? `?change=${changes.join(",")}` : ""}`,
    ),
  apply: (runId: string) =>
    api<TreasurySyncRun>(`/api/v1/admin/treasury-sync/${runId}/apply`, { method: "POST" }),
  cancel: (runId: string) =>
    api<TreasurySyncRun>(`/api/v1/admin/treasury-sync/${runId}/cancel`, { method: "POST" }),
};
