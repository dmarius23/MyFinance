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
  allocatedAmount: number | null;
  invoiceRemaining: number | null;
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
  allocatedAmount: number;
  remainingAmount: number;
  fullyAllocated: boolean;
  matchedInvoices: MatchedInvoice[];
  category: string | null;
  decisionSource: string | null;
  reason: string;
}

export interface CompanyCompleteness {
  companyId: string;
  completeness: "NOT_STARTED" | "PARTIAL" | "COMPLETE";
  // Payment/matching roll-up over the company's invoices/receipts for the period.
  payment: "NONE" | "PARTIAL" | "COMPLETE";
  // Bank transactions that require a document but aren't matched yet — the docs still owed.
  missingTxnCount: number;
  // Uploaded invoices/receipts not linked to any transaction yet.
  unmatchedInvoiceCount: number;
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
  match: (companyId: string, txnId: string, invoiceId: string, amount?: number) =>
    api<void>(`/api/v1/companies/${companyId}/bank-transactions/${txnId}/matches`, {
      method: "POST",
      body: JSON.stringify({ invoiceId, amount }),
    }),
  /** Transactions still open for allocation within a rolling window (add-payment picker). */
  openTransactions: (companyId: string, period: string, months = 18) =>
    api<OpenTransaction[]>(`/api/v1/companies/${companyId}/bank-transactions/open?period=${period}&months=${months}`),
  unmatch: (companyId: string, txnId: string, invoiceId: string) =>
    api<void>(`/api/v1/companies/${companyId}/bank-transactions/${txnId}/matches/${invoiceId}`, {
      method: "DELETE",
    }),
};

export interface OpenInvoice {
  id: string;
  documentId: string;
  filename: string | null;
  supplierName: string | null;
  supplierIban: string | null;
  totalAmount: number | null;
  invoiceDate: string | null;
  periodMonth: string;
  paidAmount: number;
  remaining: number | null;
  duplicate: boolean;
}

export interface OpenTransaction {
  id: string;
  txnDate: string;
  amount: number;
  partnerName: string | null;
  partnerIban: string | null;
  allocatedAmount: number;
  remaining: number;
}

export interface InvoicePayment {
  txnId: string;
  txnDate: string;
  partnerName: string | null;
  amount: number;
  allocatedAmount: number;
}

export interface InvoicePayments {
  invoiceId: string;
  documentId: string;
  filename: string | null;
  supplierName: string | null;
  totalAmount: number | null;
  invoiceDate: string | null;
  paidAmount: number;
  remaining: number | null;
  status: "UNPAID" | "PARTIAL" | "PAID";
  payments: InvoicePayment[];
}

export const invoicesApi = {
  list: (companyId: string, period: string) =>
    api<Invoice[]>(`/api/v1/companies/${companyId}/invoices?period=${period}`),
  /** Invoices still open for payment within a rolling window (default 18 months) ending at period. */
  open: (companyId: string, period: string, months = 18) =>
    api<OpenInvoice[]>(`/api/v1/companies/${companyId}/invoices/open?period=${period}&months=${months}`),
  /** Invoice-centric payments view (applied payments + remaining), keyed by the document id. */
  paymentsByDocument: (companyId: string, documentId: string) =>
    api<InvoicePayments>(`/api/v1/companies/${companyId}/invoices/by-document/${documentId}/payments`),
};

export interface DocumentStatus {
  documentId: string;
  dateFlag: "RED" | "ORANGE" | null;
  dateReason: string | null;
  duplicate: boolean;
  paymentStatus: "UNPAID" | "PARTIAL" | "PAID" | null;
  wrongParty: boolean | null;
  clientCif: string | null;
}

export interface SuggestionLink {
  transactionId: string;
  txnDate: string;
  partnerName: string | null;
  txnAmount: number;
  invoiceId: string;
  invoiceFilename: string | null;
  supplierName: string | null;
  amount: number;
}

export interface MatchSuggestion {
  kind: "EXACT" | "SPLIT" | "INSTALLMENT";
  links: SuggestionLink[];
}

export const reconciliationApi = {
  summary: (period: string) =>
    api<CompanyCompleteness[]>(`/api/v1/reconciliation/summary?period=${period}`),
  documentStatus: (companyId: string, period: string) =>
    api<DocumentStatus[]>(`/api/v1/companies/${companyId}/document-status?period=${period}`),
  /** Non-trivial match proposals (cross-period exact, split, installment) for one-click apply. */
  suggestions: (companyId: string, period: string) =>
    api<MatchSuggestion[]>(`/api/v1/companies/${companyId}/match-suggestions?period=${period}`),
};
