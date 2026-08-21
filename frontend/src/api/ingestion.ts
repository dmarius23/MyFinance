import { api } from "../lib/apiClient";

export interface Connection {
  id: string;
  provider: string;
  displayName: string;
  rootFolderId: string;
  forcedType: string | null;
  /** true → uploaded documents are also written back into this Drive (read + write). */
  writeEnabled: boolean;
  /** Which Drive-filing structure this connection writes into (for write-enabled connections). */
  purpose: DrivePurpose;
  /** DECLARATIONS drive: true → root already IS the "Declaratii {year}" folder (skip that level). */
  rootIsYearFolder: boolean;
  status: string;
  lastSyncedAt: string | null;
  lastResult: string | null;
}

/** DECLARATIONS = declarations + payrolls + reports drive; ACCOUNTING = bank statements + invoices drive. */
export type DrivePurpose = "DECLARATIONS" | "ACCOUNTING";

export interface SyncResult {
  imported: number;
  needsReview: number;
  skipped: number;
  failed: number;
  issues?: { filename: string; reason: string }[];
}

/** Shared per (module + month) sync state — an in-progress sync is visible to every user. */
export interface SyncStatus {
  running: boolean;
  startedBy: string | null;
  startedAt: string | null;
  lastSyncedAt: string | null;
  lastResult: string | null;
}

export interface ImportRow {
  id: string;
  filename: string | null;
  sourcePath: string | null;
  status: string;
  detail: string | null;
  documentId: string | null;
  createdAt: string;
}

const base = "/api/v1/ingestion/connections";

export const ingestionApi = {
  list: () => api<Connection[]>(base),
  create: (input: { provider: string; displayName: string; rootFolderId: string; forcedType?: string | null; writeEnabled?: boolean; purpose?: DrivePurpose; rootIsYearFolder?: boolean }) =>
    api<Connection>(base, { method: "POST", body: JSON.stringify(input) }),
  update: (id: string, input: Partial<{ displayName: string; rootFolderId: string; forcedType: string | null; writeEnabled: boolean; purpose: DrivePurpose; rootIsYearFolder: boolean; status: string }>) =>
    api<Connection>(`${base}/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  remove: (id: string) => api<void>(`${base}/${id}`, { method: "DELETE" }),
  sync: (id: string) => api<SyncResult>(`${base}/${id}/sync`, { method: "POST" }),
  imports: (id: string) => api<ImportRow[]>(`${base}/${id}/imports`),

  // Staff-facing: is this document type sourced from a cloud folder, and scoped per-company sync.
  source: (type: string) => api<{ driveEnabled: boolean; driveWrite: boolean }>(`/api/v1/ingestion/source?type=${type}`),
  syncCompany: (input: { companyId: string; period: string; type: string }) =>
    api<SyncResult>(`/api/v1/ingestion/sync-company`, { method: "POST", body: JSON.stringify(input) }),
  // Bulk: sync one module (type) for a whole month across ALL companies.
  syncMonth: (input: { period: string; type: string }) =>
    api<SyncResult>(`/api/v1/ingestion/sync-month`, { method: "POST", body: JSON.stringify(input) }),
  // Shared sync state for a module + month (last synced + whether a sync is running now).
  syncStatus: (type: string, period: string) =>
    api<SyncStatus>(`/api/v1/ingestion/sync-status?type=${type}&period=${period}`),
};
