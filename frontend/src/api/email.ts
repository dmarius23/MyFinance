import { api } from "../lib/apiClient";

/** Default participants for a company's emails: From (logged-in user + firm) and recipient (representative). */
export interface EmailEnvelope {
  fromName: string | null;
  fromEmail: string | null;
  recipient: string | null;
}

export const emailApi = {
  envelope: (companyId: string) =>
    api<EmailEnvelope>(`/api/v1/companies/${companyId}/email-envelope`),
};
