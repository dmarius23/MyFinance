import { api, upload, download } from "../lib/apiClient";
import type { ReportData, TrendPoint } from "./reports";

/** Reporting period grain — mirrors the backend enum. */
export type Granularity = "MONTH" | "QUARTER" | "HALF" | "YEAR";

/** A filename/label tag for the calendar period containing {@code period} (yyyy-MM-01): 2026-03, 2026-Q2, 2026-H1, 2026. */
export function periodTag(period: string, g: Granularity): string {
  const [y, m] = period.split("-").map(Number);
  switch (g) {
    case "MONTH": return period.slice(0, 7);
    case "QUARTER": return `${y}-Q${Math.floor((m - 1) / 3) + 1}`;
    case "HALF": return `${y}-H${Math.floor((m - 1) / 6) + 1}`;
    case "YEAR": return `${y}`;
  }
}

/** The report for a period plus how much of that period is covered (server-computed). */
export interface PortalReport {
  report: ReportData | null;
  complete: boolean;
  monthsPresent: number;
  monthsExpected: number;
}

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
  /** true = money in (income → the company issues the invoice); false = money out (supplier invoice needed). */
  credit: boolean;
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
  issuerCif?: string | null;
  total?: number | null;
  invoiceDate?: string | null;
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

export interface PushConfig {
  enabled: boolean;
  publicKey: string;
}

export interface PushSubscriptionKeys {
  endpoint: string;
  p256dh: string;
  auth: string;
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
  // The report for the calendar period of `granularity` containing `period`, plus its coverage
  // (report: null when none is available yet).
  report: (period: string, granularity: Granularity = "MONTH") =>
    api<PortalReport>(`/api/v1/portal/report?period=${period}&granularity=${granularity}`),
  payroll: (period: string) => api<PayrollFile[]>(`/api/v1/portal/payroll?period=${period}`),
  balanceSheet: (period: string) => api<PortalDoc[]>(`/api/v1/portal/balance-sheet?period=${period}`),
  payments: (period: string) => api<Payment>(`/api/v1/portal/payments?period=${period}`),
  // Monthly time series (always month-stepped), optionally with `forecast` projected months appended.
  trend: (period: string, months = 12, forecast = 0) =>
    api<TrendPoint[]>(`/api/v1/portal/report/trend?period=${period}&months=${months}&forecast=${forecast}`),
  downloadReport: async (period: string, granularity: Granularity = "MONTH") =>
    saveBlob(await download(`/api/v1/portal/report/pdf?period=${period}&granularity=${granularity}`),
      `raport-${periodTag(period, granularity)}.pdf`),
  downloadFile: async (id: string, filename: string) =>
    saveBlob(await download(`/api/v1/portal/files/${id}`), filename),
  // Same content as a Blob, for in-app preview (no download).
  fileBlob: (id: string) => download(`/api/v1/portal/files/${id}`),
  reportBlob: (period: string, granularity: Granularity = "MONTH") =>
    download(`/api/v1/portal/report/pdf?period=${period}&granularity=${granularity}`),
  notifications: () => api<PortalNotification[]>("/api/v1/portal/notifications"),
  markNotificationRead: (id: string) => api<void>(`/api/v1/portal/notifications/${id}/read`, { method: "POST" }),
  // Web Push (VAPID) — config (public key + whether the server has push enabled) and (un)subscribe.
  pushConfig: () => api<PushConfig>("/api/v1/portal/push/config"),
  subscribePush: (body: PushSubscriptionKeys) =>
    api<void>("/api/v1/portal/push/subscriptions", { method: "POST", body: JSON.stringify(body) }),
  unsubscribePush: (endpoint: string) =>
    api<void>("/api/v1/portal/push/subscriptions", { method: "DELETE", body: JSON.stringify({ endpoint }) }),
};
