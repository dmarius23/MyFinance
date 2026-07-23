import { api, apiWithHeaders, download } from "../lib/apiClient";
import { periodTag, type Granularity } from "./portal";

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

const MONTHS_IN: Record<Granularity, number> = { MONTH: 1, QUARTER: 3, HALF: 6, YEAR: 12 };

/** The computed report plus how much of the requested period is covered (from the X-Report-* headers). */
export interface ReportWithCoverage {
  report: ReportData | null;
  complete: boolean;
  monthsPresent: number;
  monthsExpected: number;
}

export const reportsApi = {
  list: (period: string) => api<ReportRow[]>(`/api/v1/reports?period=${period}`),
  report: async (companyId: string, period: string, granularity: Granularity = "MONTH"): Promise<ReportWithCoverage> => {
    const { data, headers } = await apiWithHeaders<ReportData>(
      `/api/v1/companies/${companyId}/report?period=${period}&granularity=${granularity}`,
    );
    const num = (h: string, fallback: number) => {
      const v = headers.get(h);
      return v == null ? fallback : Number(v);
    };
    return {
      report: data ?? null,
      complete: headers.get("X-Report-Complete") === "true",
      monthsPresent: num("X-Report-Months-Present", 0),
      monthsExpected: num("X-Report-Months-Expected", MONTHS_IN[granularity]),
    };
  },
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
