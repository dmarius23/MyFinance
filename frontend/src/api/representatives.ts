import { api } from "../lib/apiClient";

export interface Representative {
  id: string;
  name: string | null;
  email: string;
  phone: string | null;
  status: "ACTIVE" | "INACTIVE" | "INVITED";
}

export const representativesApi = {
  list: (companyId: string) =>
    api<Representative[]>(`/api/v1/companies/${companyId}/representatives`),
  invite: (companyId: string, input: { name: string; email: string; phone?: string }) =>
    api<Representative>(`/api/v1/companies/${companyId}/representatives`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
};
