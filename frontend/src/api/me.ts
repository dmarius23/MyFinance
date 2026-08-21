import { api } from "../lib/apiClient";

/** The signed-in user's own tenant (accounting firm) identity, for the app shell. */
export interface Me {
  tenantName: string | null;
  cui: string | null;
}

export const meApi = {
  get: () => api<Me>("/api/v1/me"),
};
