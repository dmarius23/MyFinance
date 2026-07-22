import { api, download } from "../lib/apiClient";

export interface ReportItem {
  code: string | null;
  label: string;
  amount: number;
}

export interface ReportData {
  companyName: string | null;
  cui: string | null;
  periodStart: string | null;
  periodEnd: string | null;
  balanced: boolean;
  profitLoss: {
    revenue: number;
    revenueItems: ReportItem[];
    operatingExpenses: number;
    expenseItems: ReportItem[];
    grossProfit: number;
    incomeTax: number;
    netProfit: number;
  };
  balanceSheet: {
    assets: ReportItem[];
    totalAssets: number;
    liabilities: ReportItem[];
    totalLiabilities: number;
    equity: ReportItem[];
    totalEquity: number;
  };
  kpis: {
    grossMargin: number | null;
    netMargin: number | null;
    currentAssets: number;
    currentLiabilities: number;
    currentRatio: number | null;
    debtToEquity: number | null;
  };
}

/** Per-company report status for the monthly list. */
export interface ReportRow {
  companyId: string;
  uploadedAt: string | null;
  version: number;
  balanced: boolean;
  lastSentAt: string | null;
  sentCount: number;
}

export interface TrendPoint {
  periodMonth: string;
  revenue: number;
  expenses: number;
  netProfit: number;
}

export interface ReportEmailView {
  id: string;
  recipient: string | null;
  status: "QUEUED" | "SENT" | "FAILED";
  sentAt: string;
  body: string;
}

/** Trigger a browser download for a fetched blob. */
function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export const reportsApi = {
  list: (period: string) => api<ReportRow[]>(`/api/v1/reports?period=${period}`),
  report: (companyId: string, period: string) =>
    api<ReportData>(`/api/v1/companies/${companyId}/report?period=${period}`),
  trend: (companyId: string, period: string, months = 12) =>
    api<TrendPoint[]>(`/api/v1/companies/${companyId}/report/trend?period=${period}&months=${months}`),
  downloadPdf: async (companyId: string, period: string) => {
    const blob = await download(`/api/v1/companies/${companyId}/report/pdf?period=${period}`);
    saveBlob(blob, `raport-financiar-${period.slice(0, 7)}.pdf`);
  },
  emailBody: (companyId: string, period: string) =>
    api<{ body: string }>(`/api/v1/companies/${companyId}/report/email-body?period=${period}`),
  history: (companyId: string, period: string) =>
    api<ReportEmailView[]>(`/api/v1/companies/${companyId}/report-emails?period=${period}`),
  send: (companyId: string, input: { period: string; recipient: string; body: string }) =>
    api<ReportEmailView>(`/api/v1/companies/${companyId}/report-emails`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
};
