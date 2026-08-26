import { api, download } from "../lib/apiClient";
import { periodTag, type Granularity } from "./portal";
import type { Page } from "./companies";
import type { CompletenessFilter } from "./payroll";

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
  balanceCount: number;
  balanceFiles: string[];
  lastWhatsappAt: string | null;
  whatsappCount: number;
}

/** A self-contained row for the paginated + filterable Reports list (embeds the company identity). */
export interface ReportListRow extends ReportRow {
  companyName: string;
  cui: string | null;
  locality: string | null;
}

export interface TrendPoint {
  periodMonth: string;
  revenue: number;
  expenses: number;
  netProfit: number;
  /** true for forecast points (a non-authoritative estimate); false for actuals. */
  projected: boolean;
  /** Confidence band on the charted lines — null on actuals or when too few points to estimate. */
  revenueLow: number | null;
  revenueHigh: number | null;
  netProfitLow: number | null;
  netProfitHigh: number | null;
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

/** The computed report plus how much of the requested period is covered (server-computed). */
export interface ReportWithCoverage {
  report: ReportData | null;
  complete: boolean;
  monthsPresent: number;
  monthsExpected: number;
}

export const reportsApi = {
  list: (period: string) => api<ReportRow[]>(`/api/v1/reports?period=${period}`),
  /** Paginated + fuzzy-searchable list; filter="missing" narrows to active companies with no trial balance. */
  listPage: (period: string, q: string, filter: CompletenessFilter, page: number, size = 25) =>
    api<Page<ReportListRow>>(
      `/api/v1/reports/page?period=${period}&q=${encodeURIComponent(q)}&filter=${filter}&page=${page}&size=${size}`),
  // Rebuild reports for the month from already-imported trial balances (re-runs extraction, no re-import).
  rebuild: (period: string) =>
    api<{ reprocessed: number }>(`/api/v1/documents/reprocess`, {
      method: "POST",
      body: JSON.stringify({ period, type: "TRIAL_BALANCE" }),
    }),
  report: (companyId: string, period: string, granularity: Granularity = "MONTH") =>
    api<ReportWithCoverage>(`/api/v1/companies/${companyId}/report?period=${period}&granularity=${granularity}`),
  trend: (companyId: string, period: string, months = 12, forecast = 0) =>
    api<TrendPoint[]>(`/api/v1/companies/${companyId}/report/trend?period=${period}&months=${months}&forecast=${forecast}`),
  downloadPdf: async (companyId: string, period: string, granularity: Granularity = "MONTH") => {
    const blob = await download(`/api/v1/companies/${companyId}/report/pdf?period=${period}&granularity=${granularity}`);
    saveBlob(blob, `raport-financiar-${periodTag(period, granularity)}.pdf`);
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
