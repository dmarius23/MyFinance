import { api } from "../lib/apiClient";

export interface Connection {
  id: string;
  provider: string;
  displayName: string;
  rootFolderId: string;
  forcedType: string | null;
  status: string;
  lastSyncedAt: string | null;
  lastResult: string | null;
}

export interface SyncResult {
  imported: number;
  needsReview: number;
  skipped: number;
  failed: number;
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
  create: (input: { provider: string; displayName: string; rootFolderId: string; forcedType?: string | null }) =>
    api<Connection>(base, { method: "POST", body: JSON.stringify(input) }),
  update: (id: string, input: Partial<{ displayName: string; rootFolderId: string; forcedType: string | null; status: string }>) =>
    api<Connection>(`${base}/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  remove: (id: string) => api<void>(`${base}/${id}`, { method: "DELETE" }),
  sync: (id: string) => api<SyncResult>(`${base}/${id}/sync`, { method: "POST" }),
  imports: (id: string) => api<ImportRow[]>(`${base}/${id}/imports`),
};
