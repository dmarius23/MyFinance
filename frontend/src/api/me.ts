import { api } from "../lib/apiClient";

/** The signed-in user's own tenant (accounting firm) identity + capabilities, for the app shell. */
export interface Me {
  tenantName: string | null;
  cui: string | null;
  /** Whether the tenant has configured & enabled its own SMTP provider — email-send actions are
   *  disabled until this is true (the firm must set up SMTP under Settings before sending). */
  emailConfigured: boolean;
}

export const meApi = {
  get: () => api<Me>("/api/v1/me"),
};
