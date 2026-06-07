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
};

export const reconciliationApi = {
  summary: (period: string) =>
    api<CompanyCompleteness[]>(`/api/v1/reconciliation/summary?period=${period}`),
};
