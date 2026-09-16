import { api } from "../lib/apiClient";

/** The signed-in user's own tenant (accounting firm) identity + capabilities, for the app shell. */
export interface Me {
  tenantName: string | null;
  cui: string | null;
  /** Whether the tenant has configured & enabled its own SMTP provider — email-send actions are
   *  disabled until this is true (the firm must set up SMTP under Settings before sending). */
  emailConfigured: boolean;
  /** Whether the tenant has a usable WhatsApp provider (Twilio with credentials, or click-to-chat) —
   *  WhatsApp-send actions are disabled until this is true. */
  whatsappConfigured: boolean;
  /** The tenant's WhatsApp mode. CLICK_TO_CHAT opens a wa.me deep link (manual send) instead of the
   *  compose modal + backend send used by TWILIO. */
  whatsappMode: "OFF" | "TWILIO" | "CLICK_TO_CHAT";
}

export const meApi = {
  get: () => api<Me>("/api/v1/me"),
};
