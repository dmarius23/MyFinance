import { api, upload, download } from "../lib/apiClient";
import type { ReportData } from "./reports";

export interface PortalCompany {
  companyId: string;
  name: string | null;
  cui: string | null;
}

export interface MissingItem {
  txnDate: string;
  partnerName: string | null;
  amount: number;
  description: string | null;
}

export interface PortalDoc {
  id: string;
  filename: string;
  type: string;
  status: string;
  uploadedAt: string;
}

export interface PayrollFile {
  id: string;
  filename: string;
}

function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url; a.download = filename;
  document.body.appendChild(a); a.click(); a.remove();
  URL.revokeObjectURL(url);
}

export const portalApi = {
  me: () => api<PortalCompany>("/api/v1/portal/me"),
  uploadDocument: (file: File, periodMonth?: string) => {
    const form = new FormData();
    form.append("file", file);
    if (periodMonth) form.append("periodMonth", periodMonth);
    return upload<PortalDoc>("/api/v1/portal/documents", form);
  },
  missing: (period: string) => api<MissingItem[]>(`/api/v1/portal/missing?period=${period}`),
  myDocuments: (period: string) => api<PortalDoc[]>(`/api/v1/portal/documents?period=${period}`),
  // 204 → undefined (no report for the period yet).
  report: (period: string) => api<ReportData | null>(`/api/v1/portal/report?period=${period}`),
  payroll: (period: string) => api<PayrollFile[]>(`/api/v1/portal/payroll?period=${period}`),
  downloadReport: async (period: string) =>
    saveBlob(await download(`/api/v1/portal/report/pdf?period=${period}`), `raport-${period.slice(0, 7)}.pdf`),
  downloadFile: async (id: string, filename: string) =>
    saveBlob(await download(`/api/v1/portal/files/${id}`), filename),
};
