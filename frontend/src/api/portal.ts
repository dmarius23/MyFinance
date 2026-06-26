import { api, upload, download } from "../lib/apiClient";
import type { ReportData, TrendPoint } from "./reports";

export interface CompanyOption {
  companyId: string;
  name: string | null;
  cui: string | null;
}

export interface PortalCompany {
  companyId: string;
  name: string | null;
  cui: string | null;
  companies: CompanyOption[];
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
  issuer?: string | null;
  paymentStatus?: "PAID" | "PARTIAL" | "UNPAID" | null;
  duplicate?: boolean;
  outsidePeriod?: boolean;
}

export interface PayrollFile {
  id: string;
  filename: string;
}

export interface PaymentLine {
  amount: number;
  explanation: string | null;
  iban: string;
  scadenta: string | null;
  categories: string[];
}

export interface Payment {
  total: number;
  lines: PaymentLine[];
  unconfigured: { category: string; amount: number }[];
}

export interface PortalNotification {
  id: string;
  type: string;
  title: string;
  body: string;
  companyName: string | null;
  readAt: string | null;
  createdAt: string;
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
  companyDocuments: (period: string) => api<PortalDoc[]>(`/api/v1/portal/company-documents?period=${period}`),
  // 204 → undefined (no report for the period yet).
  report: (period: string) => api<ReportData | null>(`/api/v1/portal/report?period=${period}`),
  payroll: (period: string) => api<PayrollFile[]>(`/api/v1/portal/payroll?period=${period}`),
  balanceSheet: (period: string) => api<PortalDoc[]>(`/api/v1/portal/balance-sheet?period=${period}`),
  payments: (period: string) => api<Payment>(`/api/v1/portal/payments?period=${period}`),
  trend: (period: string, months = 12) => api<TrendPoint[]>(`/api/v1/portal/report/trend?period=${period}&months=${months}`),
  downloadReport: async (period: string) =>
    saveBlob(await download(`/api/v1/portal/report/pdf?period=${period}`), `raport-${period.slice(0, 7)}.pdf`),
  downloadFile: async (id: string, filename: string) =>
    saveBlob(await download(`/api/v1/portal/files/${id}`), filename),
  // Same content as a Blob, for in-app preview (no download).
  fileBlob: (id: string) => download(`/api/v1/portal/files/${id}`),
  reportBlob: (period: string) => download(`/api/v1/portal/report/pdf?period=${period}`),
  notifications: () => api<PortalNotification[]>("/api/v1/portal/notifications"),
  markNotificationRead: (id: string) => api<void>(`/api/v1/portal/notifications/${id}/read`, { method: "POST" }),
};
