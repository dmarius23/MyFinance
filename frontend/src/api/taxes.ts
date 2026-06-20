import { api } from "../lib/apiClient";

export interface DeclarationSummary {
  id: string;
  documentId: string;
  type: "D100" | "D112" | "D300";
  computedTotal: number;
  declaredTotal: number | null;
  mismatch: boolean;
  sentCount: number;
  lastSentAt: string | null;
}

export interface PaymentLine {
  iban: string;
  amount: number;
  categories: string[];
  explanation: string;
  scadenta: string | null;
}

export interface Unconfigured {
  category: string;
  amount: number;
}

export interface EmailView {
  id: string;
  recipient: string | null;
  status: "SENT" | "FAILED";
  sentAt: string;
  declarationIds: string[];
  body: string;
}

export interface TaxPaymentSummary {
  companyId: string;
  companyName: string;
  cui: string;
  period: string;
  beneficiary: string | null;
  declarations: DeclarationSummary[];
  paymentLines: PaymentLine[];
  unconfigured: Unconfigured[];
  totalToPay: number;
  emailBody: string | null;
  emails: EmailView[];
}

export interface EmailPreview {
  body: string | null;
  total: number;
  unconfigured: Unconfigured[];
}

export const taxPaymentsApi = {
  summary: (companyId: string, period: string) =>
    api<TaxPaymentSummary>(`/api/v1/companies/${companyId}/tax-payments?period=${period}`),

  previewEmail: (companyId: string, declarationIds: string[]) =>
    api<EmailPreview>(`/api/v1/companies/${companyId}/tax-emails/preview`, {
      method: "POST",
      body: JSON.stringify({ declarationIds }),
    }),

  sendEmail: (companyId: string, input: { period: string; declarationIds: string[]; recipient: string; body: string }) =>
    api<EmailView>(`/api/v1/companies/${companyId}/tax-emails`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
};
