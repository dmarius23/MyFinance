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
  update: (companyId: string, userId: string, input: { name: string; phone?: string }) =>
    api<Representative>(`/api/v1/companies/${companyId}/representatives/${userId}`, {
      method: "PUT",
      body: JSON.stringify(input),
    }),
  setActive: (companyId: string, userId: string, active: boolean) =>
    api<Representative>(`/api/v1/companies/${companyId}/representatives/${userId}/active`, {
      method: "PATCH",
      body: JSON.stringify({ active }),
    }),
  remove: (companyId: string, userId: string) =>
    api<void>(`/api/v1/companies/${companyId}/representatives/${userId}`, { method: "DELETE" }),
};
