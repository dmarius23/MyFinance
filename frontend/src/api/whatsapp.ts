import { api } from "../lib/apiClient";

/** Module discriminator — mirrors the backend EmailKind used to keep each module's history separate. */
export type WhatsAppKind = "TAX" | "PAYROLL" | "REPORT" | "DOCUMENT_REMINDER";
export type WhatsAppSourceKind =
  | "TAX"
  | "PAYROLL"
  | "REPORT"
  | "REMINDER_NOTHING_RECEIVED"
  | "REMINDER_DOCS_MISSING"
  | "REMINDER_DOCS_ONLY";

export interface WhatsAppView {
  id: string;
  recipient: string | null;
  status: "QUEUED" | "SENT" | "FAILED";
  sentAt: string;
  body: string;
  providerStatus?: "accepted" | "sent" | "delivered" | "read" | "failed";
  providerStatusAt?: string | null;
  /** Why the last delivery attempt failed (set while retrying and on final failure). */
  error?: string | null;
}

/** A company document that can be sent after the template message (PDF or image). */
export interface WhatsAppAttachment {
  id: string;
  filename: string;
}

export interface WhatsAppMetaPreview {
  name: string;
  appLanguage: string;
  metaLanguage: string;
  status: string;
  body: string | null;
  variableNames: string[];
}

export const whatsappApi = {
  /** The resolved recipient phone (the company's representative), to pre-fill the compose form. */
  recipient: (companyId: string) =>
    api<{ phone: string | null }>(`/api/v1/companies/${companyId}/whatsapp/recipient`),
  metaPreview: (companyId: string, kind: WhatsAppKind, sourceKind?: WhatsAppSourceKind, language = "ro_RO") =>
    api<WhatsAppMetaPreview>(
      `/api/v1/companies/${companyId}/whatsapp/preview?kind=${kind}`
      + (sourceKind ? `&sourceKind=${sourceKind}` : "")
      + `&language=${language}`),
  /** WhatsApp send history for a module + company + period (newest first). */
  history: (companyId: string, kind: WhatsAppKind, period: string) =>
    api<WhatsAppView[]>(`/api/v1/companies/${companyId}/whatsapp?kind=${kind}&period=${period}`),
  /** Send a WhatsApp message for a module + company + period. */
  send: (companyId: string, input: {
    kind: WhatsAppKind;
    sourceKind?: WhatsAppSourceKind;
    period: string;
    language?: string;
    templateVariables?: Record<string, string>;
    attachmentDocumentIds?: string[];
    body: string;
  }) =>
    api<WhatsAppView>(`/api/v1/companies/${companyId}/whatsapp`, {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() },
      body: JSON.stringify(input),
    }),
};
