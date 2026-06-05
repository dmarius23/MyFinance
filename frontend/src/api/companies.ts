import { api } from "../lib/apiClient";

export interface Company {
  id: string;
  legalName: string;
  cui: string;
  entityType: string | null;
  locality: string | null;
  vatStatus: string | null;
  vatPeriod: string | null;
  responsibleUserId: string | null;
  status: "ACTIVE" | "INACTIVE";
}

export interface CreateCompanyInput {
  legalName: string;
  cui: string;
  entityType?: string;
  locality?: string;
  vatStatus?: string;
  vatPeriod?: string;
  responsibleUserId?: string;
}

export const companiesApi = {
  list: () => api<Company[]>("/api/v1/companies"),
  get: (id: string) => api<Company>(`/api/v1/companies/${id}`),
  create: (input: CreateCompanyInput) =>
    api<Company>("/api/v1/companies", { method: "POST", body: JSON.stringify(input) }),
  update: (id: string, input: Partial<CreateCompanyInput>) =>
    api<Company>(`/api/v1/companies/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  setStatus: (id: string, status: "ACTIVE" | "INACTIVE") =>
    api<Company>(`/api/v1/companies/${id}/status`, {
      method: "PATCH",
      body: JSON.stringify({ status }),
    }),
};
