import { api } from "../lib/apiClient";

/**
 * Per-tenant message wording. `customized` is false when the text returned is the built-in default,
 * so the UI offers "Reset to default" only when there is an override to drop.
 */

export type TemplateKind =
  | "TAX"
  | "REPORT"
  | "PAYROLL"
  | "REMINDER_NOTHING_RECEIVED"
  | "REMINDER_DOCS_MISSING"
  | "REMINDER_DOCS_ONLY";

export interface MessageTemplate {
  kind: TemplateKind;
  subject: string;
  body: string;
  customized: boolean;
  /** Scalar names this kind's template may use, e.g. "companyName". */
  placeholders: string[];
  /** Multi-line lists the server renders, e.g. "missingTransactions". */
  blocks: string[];
}

export interface UpdateTemplateInput {
  /** Blank keeps the built-in subject. */
  subject: string;
  body: string;
}

export interface TemplatePreview {
  subject: string;
  body: string;
  sampleValues: Record<string, string>;
}

const base = "/api/v1/settings/messaging/templates";

export const templatesApi = {
  list: () => api<MessageTemplate[]>(base),
  update: (kind: TemplateKind, input: UpdateTemplateInput) =>
    api<MessageTemplate>(`${base}/${kind}`, { method: "PUT", body: JSON.stringify(input) }),
  reset: (kind: TemplateKind) => api<MessageTemplate>(`${base}/${kind}`, { method: "DELETE" }),
  preview: (kind: TemplateKind, input: UpdateTemplateInput) =>
    api<TemplatePreview>(`${base}/${kind}/preview`, { method: "POST", body: JSON.stringify(input) }),
};
