import { api } from "../lib/apiClient";

/** Module discriminator — mirrors the backend EmailKind used to keep each module's history separate. */
export type WhatsAppKind = "TAX" | "PAYROLL" | "REPORT" | "DOCUMENT_REMINDER";

export interface WhatsAppView {
  id: string;
  recipient: string | null;
  status: "QUEUED" | "SENT" | "FAILED";
  sentAt: string;
  body: string;
}

export const whatsappApi = {
  /** The resolved recipient phone (the company's representative), to pre-fill the compose form. */
  recipient: (companyId: string) =>
    api<{ phone: string | null }>(`/api/v1/companies/${companyId}/whatsapp/recipient`),
  /** WhatsApp send history for a module + company + period (newest first). */
  history: (companyId: string, kind: WhatsAppKind, period: string) =>
    api<WhatsAppView[]>(`/api/v1/companies/${companyId}/whatsapp?kind=${kind}&period=${period}`),
  /** Send a WhatsApp message for a module + company + period. */
  send: (companyId: string, input: { kind: WhatsAppKind; period: string; recipient?: string; body: string }) =>
    api<WhatsAppView>(`/api/v1/companies/${companyId}/whatsapp`, { method: "POST", body: JSON.stringify(input) }),
};
