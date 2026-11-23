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

/** One server-filled template variable: its {{position}}, placeholder name and value. */
export interface WhatsAppTemplateVariable {
  position: number;
  name: string;
  value: string;
}

export interface WhatsAppMetaPreview {
  name: string;
  /** The mapping that matched (may be the fallback language) — pass it back on send. */
  appLanguage: string;
  metaLanguage: string;
  status: string;
  /** The Meta template text with this company's values filled in — what the client will read. */
  body: string | null;
  variableNames: string[];
  variables: WhatsAppTemplateVariable[];
  /** Why this template cannot be sent yet; null when it can. */
  blockedReason: string | null;
}

export interface WhatsAppSendInput {
  kind: WhatsAppKind;
  sourceKind?: WhatsAppSourceKind;
  period: string;
  language?: string;
  attachmentDocumentIds?: string[];
  body: string;
}

export const whatsappApi = {
  /** The resolved recipient phone (the company's representative), to pre-fill the compose form. */
  recipient: (companyId: string) =>
    api<{ phone: string | null }>(`/api/v1/companies/${companyId}/whatsapp/recipient`),
  metaPreview: (companyId: string, kind: WhatsAppKind, period: string, sourceKind?: WhatsAppSourceKind,
    language = "ro_RO") =>
    api<WhatsAppMetaPreview>(
      `/api/v1/companies/${companyId}/whatsapp/preview?kind=${kind}`
      + (sourceKind ? `&sourceKind=${sourceKind}` : "")
      + `&language=${language}&period=${encodeURIComponent(period)}`),
  /** WhatsApp send history for a module + company + period (newest first). */
  history: (companyId: string, kind: WhatsAppKind, period: string) =>
    api<WhatsAppView[]>(`/api/v1/companies/${companyId}/whatsapp?kind=${kind}&period=${period}`),
  /**
   * Send a WhatsApp message for a module + company + period. Template variables are filled server-side.
   * `idempotencyKey` must be reused when retrying the same request (see `createIdempotencyKeys`), so a
   * retry after a lost response returns the original message instead of queueing a duplicate.
   */
  send: (companyId: string, input: WhatsAppSendInput, idempotencyKey: string) =>
    api<WhatsAppView>(`/api/v1/companies/${companyId}/whatsapp`, {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(input),
    }),
};
