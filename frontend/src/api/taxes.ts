import { api } from "../lib/apiClient";

export interface DeclarationSummary {
  documentId: string;
  filename: string;
  type: "D100" | "D112" | "D300";
  computedTotal: number;
  declaredTotal: number | null;
  mismatch: boolean;
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
}

export const taxPaymentsApi = {
  summary: (companyId: string, period: string) =>
    api<TaxPaymentSummary>(`/api/v1/companies/${companyId}/tax-payments?period=${period}`),
};
