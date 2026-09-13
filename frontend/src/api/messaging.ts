import { api } from "../lib/apiClient";

/**
 * Per-tenant messaging provider settings (email SMTP + WhatsApp), managed by the firm admin.
 * Secrets are write-only: reads return only `hasPassword`/`hasToken`; on update, send the secret to
 * replace it, `""` to clear it, or omit it (undefined) to keep the stored value.
 */

export interface EmailProvider {
  enabled: boolean;
  fromEmail: string | null;
  fromName: string | null;
  smtpHost: string | null;
  smtpPort: number | null;
  smtpUsername: string | null;
  /** Whether an SMTP password is stored (the value itself is never returned). */
  hasPassword: boolean;
}

export interface UpdateEmailInput {
  enabled: boolean;
  fromEmail: string | null;
  fromName: string | null;
  smtpHost: string | null;
  smtpPort: number | null;
  smtpUsername: string | null;
  /** null/undefined = keep stored password; "" = clear; any value = replace. */
  smtpPassword?: string | null;
}

export type WhatsAppMode = "OFF" | "TWILIO" | "CLICK_TO_CHAT";

export interface WhatsAppProvider {
  mode: WhatsAppMode;
  accountSid: string | null;
  fromNumber: string | null;
  /** Whether a Twilio auth token is stored (the value itself is never returned). */
  hasToken: boolean;
}

export interface UpdateWhatsAppInput {
  mode: WhatsAppMode;
  accountSid: string | null;
  /** null/undefined = keep stored token; "" = clear; any value = replace. */
  authToken?: string | null;
  fromNumber: string | null;
}

export const messagingApi = {
  getEmail: () => api<EmailProvider>("/api/v1/settings/messaging/email"),
  updateEmail: (input: UpdateEmailInput) =>
    api<EmailProvider>("/api/v1/settings/messaging/email", {
      method: "PUT",
      body: JSON.stringify(input),
    }),
  testEmail: (to: string) =>
    api<void>("/api/v1/settings/messaging/email/test", {
      method: "POST",
      body: JSON.stringify({ to }),
    }),
  getWhatsApp: () => api<WhatsAppProvider>("/api/v1/settings/messaging/whatsapp"),
  updateWhatsApp: (input: UpdateWhatsAppInput) =>
    api<WhatsAppProvider>("/api/v1/settings/messaging/whatsapp", {
      method: "PUT",
      body: JSON.stringify(input),
    }),
};
