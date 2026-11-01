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

export type WhatsAppMode = "OFF" | "TWILIO" | "META_CLOUD_API" | "CLICK_TO_CHAT";

export interface WhatsAppProvider {
  mode: WhatsAppMode;
  accountSid: string | null;
  fromNumber: string | null;
  metaBusinessPortfolioId?: string | null;
  metaWabaId?: string | null;
  metaPhoneNumberId?: string | null;
  hasMetaToken?: boolean;
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

export type WhatsAppTemplateKind = "TAX" | "PAYROLL" | "REPORT" | "DOCUMENT_REMINDER";
export type WhatsAppTemplateLanguage = "ro_RO" | "en_US";
export type WhatsAppTemplateStatus = "PENDING" | "APPROVED" | "DISABLED";

/** A Meta-approved template registered for one message kind + language (unique per tenant). */
export interface WhatsAppTemplate {
  id: string;
  kind: WhatsAppTemplateKind;
  language: WhatsAppTemplateLanguage;
  metaTemplateName: string;
  category: "UTILITY" | "MARKETING" | "AUTHENTICATION";
  status: WhatsAppTemplateStatus;
  version: string | null;
  variableSchema: string | null;
  approvedAt: string | null;
}

/** Upserts by kind + language. */
export interface SaveWhatsAppTemplateInput {
  kind: WhatsAppTemplateKind;
  language: WhatsAppTemplateLanguage;
  metaTemplateName: string;
  category: "UTILITY";
  status: WhatsAppTemplateStatus;
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
  startMetaOnboarding: () =>
    api<{ authorizationUrl: string; expiresAt: string }>(
      "/api/v1/settings/messaging/whatsapp/meta/onboarding",
      { method: "POST" },
    ),
  completeMetaOnboarding: (input: {
    state: string;
    authorizationCode: string;
    wabaId?: string;
    phoneNumberId?: string;
    businessPortfolioId?: string;
  }) =>
    api<WhatsAppProvider>("/api/v1/settings/messaging/whatsapp/meta/onboarding/complete", {
      method: "POST",
      body: JSON.stringify(input),
    }),
  testWhatsApp: () =>
    api<{ status: "VALID" }>("/api/v1/settings/messaging/whatsapp/test", {
      method: "POST",
    }),
  listWhatsAppTemplates: () => api<WhatsAppTemplate[]>("/api/v1/settings/messaging/whatsapp/templates"),
  saveWhatsAppTemplate: (input: SaveWhatsAppTemplateInput) =>
    api<WhatsAppTemplate>("/api/v1/settings/messaging/whatsapp/templates", {
      method: "PUT",
      body: JSON.stringify(input),
    }),
  deleteWhatsAppTemplate: (id: string) =>
    api<void>(`/api/v1/settings/messaging/whatsapp/templates/${id}`, { method: "DELETE" }),
};
