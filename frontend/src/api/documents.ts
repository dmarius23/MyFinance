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
  upload: (companyId: string, periodMonth: string, file: File) => {
    const form = new FormData();
    form.append("file", file);
    form.append("periodMonth", periodMonth);
    return upload<Document>(`/api/v1/companies/${companyId}/documents`, form);
  },
  download: (companyId: string, id: string) =>
    download(`/api/v1/companies/${companyId}/documents/${id}/content`),
  remove: (companyId: string, id: string) =>
    api<void>(`/api/v1/companies/${companyId}/documents/${id}`, { method: "DELETE" }),
};

export interface CompanyDocSummary {
  companyId: string;
  hasBankStatement: boolean;
  hasInvoiceOrReceipt: boolean;
  fileCount: number;
}

export const documentsSummaryApi = {
  summary: (period: string) =>
    api<CompanyDocSummary[]>(`/api/v1/documents/summary?period=${period}`),
};
