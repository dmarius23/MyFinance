import { api } from "../lib/apiClient";

export interface BankStatement {
  id: string;
  bankCode: string | null;
  accountIban: string | null;
  openingBalance: number | null;
  closingBalance: number | null;
  status: string;
  crossCheckOk: boolean;
  txnCount: number;
}

export interface MatchedInvoice {
  invoiceId: string;
  documentId: string;
  filename: string | null;
  totalAmount: number | null;
  invoiceDate: string | null;
  supplierName: string | null;
}

export interface Invoice {
  id: string;
  documentId: string;
  filename: string | null;
  supplierName: string | null;
  supplierIban: string | null;
  totalAmount: number | null;
  invoiceDate: string | null;
  status: string;
}

export interface BankTransaction {
  id: string;
  statementId: string;
  txnDate: string;
  amount: number;
  direction: "DEBIT" | "CREDIT";
  partnerName: string | null;
  partnerIban: string | null;
  description: string | null;
  balanceAfter: number | null;
  requiresDocument: boolean;
  matched: boolean;
  matchedInvoices: MatchedInvoice[];
  category: string | null;
  decisionSource: string | null;
  reason: string;
}

export interface CompanyCompleteness {
  companyId: string;
  completeness: "NOT_STARTED" | "PARTIAL" | "COMPLETE";
}

export const bankApi = {
  statements: (companyId: string, period: string) =>
    api<BankStatement[]>(`/api/v1/companies/${companyId}/bank-statements?period=${period}`),
  transactions: (companyId: string, period: string) =>
    api<BankTransaction[]>(`/api/v1/companies/${companyId}/bank-transactions?period=${period}`),
  setRequirement: (companyId: string, txnId: string, requiresDocument: boolean, reason?: string) =>
    api<BankTransaction>(`/api/v1/companies/${companyId}/bank-transactions/${txnId}/requirement`, {
      method: "PATCH",
      body: JSON.stringify({ requiresDocument, reason }),
    }),
  match: (companyId: string, txnId: string, invoiceId: string) =>
    api<void>(`/api/v1/companies/${companyId}/bank-transactions/${txnId}/matches`, {
      method: "POST",
      body: JSON.stringify({ invoiceId }),
    }),
  unmatch: (companyId: string, txnId: string, invoiceId: string) =>
    api<void>(`/api/v1/companies/${companyId}/bank-transactions/${txnId}/matches/${invoiceId}`, {
      method: "DELETE",
    }),
};

export const invoicesApi = {
  list: (companyId: string, period: string) =>
    api<Invoice[]>(`/api/v1/companies/${companyId}/invoices?period=${period}`),
};

export interface DocumentStatus {
  documentId: string;
  warning: boolean;
  warningReason: string | null;
  unmatched: boolean;
}

export const reconciliationApi = {
  summary: (period: string) =>
    api<CompanyCompleteness[]>(`/api/v1/reconciliation/summary?period=${period}`),
  documentStatus: (companyId: string, period: string) =>
    api<DocumentStatus[]>(`/api/v1/companies/${companyId}/document-status?period=${period}`),
};
