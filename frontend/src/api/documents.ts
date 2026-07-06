import { api, upload, download } from "../lib/apiClient";

export interface Document {
  id: string;
  type: string;
  status: string;
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
  periodMonth: string;
  uploadedBy: string | null;
  uploadedAt: string;
}

export const documentsApi = {
  list: (companyId: string, period?: string) =>
    api<Document[]>(
      `/api/v1/companies/${companyId}/documents${period ? `?period=${period}` : ""}`,
    ),
  upload: (companyId: string, periodMonth: string, file: File, type?: string) => {
    const form = new FormData();
    form.append("file", file);
    form.append("periodMonth", periodMonth);
    if (type) form.append("type", type);
    return upload<Document>(`/api/v1/companies/${companyId}/documents`, form);
  },
  download: (companyId: string, id: string) =>
    download(`/api/v1/companies/${companyId}/documents/${id}/content`),
  remove: (companyId: string, id: string) =>
    api<void>(`/api/v1/companies/${companyId}/documents/${id}`, { method: "DELETE" }),
  changeType: (companyId: string, id: string, type: string) =>
    api<Document>(`/api/v1/companies/${companyId}/documents/${id}/type`, {
      method: "PATCH",
      body: JSON.stringify({ type }),
    }),
  reclassify: (companyId: string, period: string) =>
    api<number>(`/api/v1/companies/${companyId}/documents/reclassify?period=${period}`, {
      method: "POST",
    }),
  flags: (companyId: string, period: string, type: string) =>
    api<DocumentFlags[]>(`/api/v1/companies/${companyId}/documents/flags?period=${period}&type=${type}`),
  movePeriod: (companyId: string, id: string, period: string) =>
    api<Document>(`/api/v1/companies/${companyId}/documents/${id}/period`, {
      method: "PATCH",
      body: JSON.stringify({ period }),
    }),
};

/** Advisory per-document flags for the upload-manager modal. */
export interface DocumentFlags {
  documentId: string;
  wrongParty: boolean | null;
  outsidePeriod: boolean | null;
  /** The period the document actually belongs to (null if it could not be detected). */
  detectedPeriod: string | null;
}

/** Document types a user can manually assign. */
export const DOCUMENT_TYPES = [
  "BANK_STATEMENT", "INVOICE", "RECEIPT", "TRIAL_BALANCE", "DECLARATION", "PAYROLL", "UNCLASSIFIED",
] as const;

export interface CompanyDocSummary {
  companyId: string;
  hasBankStatement: boolean;
  hasInvoiceOrReceipt: boolean;
  fileCount: number;
  bankStatementCount: number;
  invoiceReceiptCount: number;
}

export const documentsSummaryApi = {
  summary: (period: string) =>
    api<CompanyDocSummary[]>(`/api/v1/documents/summary?period=${period}`),
};

/** Per-company "last reminder sent" for a period (Statements list column). */
export interface ReminderRow {
  companyId: string;
  lastSentAt: string | null;
  count: number;
}

/** One reminder send (notification log + resend). */
export interface ReminderView {
  id: string;
  recipient: string | null;
  status: "SENT" | "FAILED";
  sentAt: string;
  body: string;
}

export const remindersApi = {
  /** Per-company last-sent + count for the period. */
  list: (period: string) =>
    api<ReminderRow[]>(`/api/v1/document-reminders?period=${period}`),
  /** Full send history for one company + period (newest first). */
  history: (companyId: string, period: string) =>
    api<ReminderView[]>(`/api/v1/companies/${companyId}/document-reminders?period=${period}`),
  /** Record + dispatch one reminder. */
  send: (companyId: string, input: { period: string; recipient: string; body: string }) =>
    api<ReminderView>(`/api/v1/companies/${companyId}/document-reminders`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
};
