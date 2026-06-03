import { api } from "../lib/apiClient";

export interface Representative {
  id: string;
  email: string;
  name: string | null;
  status: "ACTIVE" | "INACTIVE" | "INVITED";
}

export const representativesApi = {
  list: (companyId: string) =>
    api<Representative[]>(`/api/v1/companies/${companyId}/representatives`),
  invite: (companyId: string, input: { email: string; name?: string }) =>
    api<Representative>(`/api/v1/companies/${companyId}/representatives`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
};
