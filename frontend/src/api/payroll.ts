import { api } from "../lib/apiClient";

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
}

/** One payroll email send (notification log + resend). */
export interface PayrollEmailView {
  id: string;
  recipient: string | null;
  status: "SENT" | "FAILED";
  sentAt: string;
  documentIds: string[];
  body: string;
}

export const payrollApi = {
  /** Per-company payroll rows for the period (docs uploaded + last-sent). */
  list: (period: string) =>
    api<PayrollRow[]>(`/api/v1/payroll?period=${period}`),
  /** Default editable email body for a company/period. */
  emailBody: (companyId: string, period: string) =>
    api<{ body: string }>(`/api/v1/companies/${companyId}/payroll/email-body?period=${period}`),
  /** Full send history for one company + period (newest first). */
  history: (companyId: string, period: string) =>
    api<PayrollEmailView[]>(`/api/v1/companies/${companyId}/payroll/emails?period=${period}`),
  /** Record + dispatch one payroll email (attaches the company's payroll documents). */
  send: (companyId: string, input: { period: string; recipient: string; body: string }) =>
    api<PayrollEmailView>(`/api/v1/companies/${companyId}/payroll/emails`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
};
