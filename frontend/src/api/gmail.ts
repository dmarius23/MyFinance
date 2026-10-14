import { api } from "../lib/apiClient";

export interface GmailConnection {
  id: string;
  displayName: string;
  mailboxAddress: string;
  status: string;
  watchExpiresAt: string | null;
  lastSyncedAt: string | null;
  lastResult: string | null;
}

export interface GmailSyncResult {
  messages: number;
  imported: number;
  needsReview: number;
  backfillRequired: boolean;
}

const base = "/api/v1/ingestion/email-connections";

export const gmailApi = {
  list: () => api<GmailConnection[]>(base),
  startNewOAuth: () =>
    api<{ connectionId: string; authorizationUrl: string; expiresAt: string }>(
      `${base}/oauth/start`,
      { method: "POST" },
    ),
  create: (input: { displayName: string; mailboxAddress: string }) =>
    api<GmailConnection>(base, { method: "POST", body: JSON.stringify(input) }),
  startOAuth: (id: string) =>
    api<{ authorizationUrl: string; expiresAt: string }>(`${base}/${id}/oauth/start`, { method: "POST" }),
  completeOAuth: (id: string, input: { state: string; authorizationCode: string }) =>
    api<void>(`${base}/${id}/oauth/complete`, { method: "POST", body: JSON.stringify(input) }),
  sync: (id: string) =>
    api<GmailSyncResult>(`${base}/${id}/sync`, { method: "POST" }),
  backfill: (id: string, input?: { fromInclusive?: string; toExclusive?: string }) =>
    api<GmailSyncResult>(`${base}/${id}/backfill`, {
      method: "POST",
      body: input ? JSON.stringify(input) : undefined,
    }),
  disconnect: (id: string) =>
    api<void>(`${base}/${id}`, { method: "DELETE" }),
};
