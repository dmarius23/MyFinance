import { api } from "../lib/apiClient";
import type { Page } from "./companies";

export interface PayrollDoc {
  id: string;
  filename: string;
}

/** Per-company payroll row for the monthly list. */
export interface PayrollRow {
  companyId: string;
  documents: PayrollDoc[];
  lastSentAt: string | null;
  sentCount: number;
  lastWhatsappAt: string | null;
  whatsappCount: number;
}

/** A self-contained row for the paginated + filterable Payroll list (embeds the company identity). */
export interface PayrollListRow extends PayrollRow {
  companyName: string;
  cui: string | null;
  locality: string | null;
}

/** Completeness filter value shared across module lists. */
export type CompletenessFilter = "all" | "missing";

/** One payroll email send (notification log + resend). */
export interface PayrollEmailView {
  id: string;
  recipient: string | null;
  status: "QUEUED" | "SENT" | "FAILED";
  sentAt: string;
  documentIds: string[];
  body: string;
}

export const payrollApi = {
  /** Per-company payroll rows for the period (docs uploaded + last-sent). */
  list: (period: string) =>
    api<PayrollRow[]>(`/api/v1/payroll?period=${period}`),
  /** Paginated + fuzzy-searchable list; filter="missing" narrows to companies that owe payroll but uploaded nothing. */
  listPage: (period: string, q: string, filter: CompletenessFilter, page: number, size = 25) =>
    api<Page<PayrollListRow>>(
      `/api/v1/payroll/page?period=${period}&q=${encodeURIComponent(q)}&filter=${filter}&page=${page}&size=${size}`),
  /** Default editable email body for a company/period. */
  emailBody: (companyId: string, period: string) =>
    api<{ body: string }>(`/api/v1/companies/${companyId}/payroll/email-body?period=${period}`),
  /** Full send history for one company + period (newest first). */
  history: (companyId: string, period: string) =>
    api<PayrollEmailView[]>(`/api/v1/companies/${companyId}/payroll/emails?period=${period}`),
  /** Record + dispatch one payroll email. documentIds = which payroll docs to attach (omit = all). */
  send: (companyId: string, input: { period: string; recipient: string; body: string; documentIds?: string[] }) =>
    api<PayrollEmailView>(`/api/v1/companies/${companyId}/payroll/emails`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
};
