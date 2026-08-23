import { api } from "../lib/apiClient";

export interface Company {
  id: string;
  legalName: string;
  cui: string;
  entityType: string | null;
  locality: string | null;
  vatStatus: string | null;
  vatPeriod: string | null;
  taxRegime: string | null; // PROFIT | MICRO
  hasEmployees: boolean | null;
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
  taxRegime?: string;
  hasEmployees?: boolean | null;
  responsibleUserId?: string;
}

/** Profit-tax base: impozit pe profit vs impozit pe venit/încasări (micro). */
export const TAX_REGIMES = ["PROFIT", "MICRO"] as const;
export const taxRegimeKey = (code: string) => `taxRegime.${code}`;

export interface CompanyRepEntry {
  companyId: string;
  id: string;
  name: string;
  email: string;
  status: string;
}

export const representativesApi = {
  /** All reps for every company in one call — used by the Companies list page. */
  listAll: () => api<CompanyRepEntry[]>("/api/v1/representatives"),
};

/** Stable paged envelope returned by paginated list endpoints (mirrors backend PageResponse). */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export const companiesApi = {
  list: () => api<Company[]>("/api/v1/companies"),
  /** Paged, fuzzy-searchable directory (name or CUI) — infinite scroll on the Companies screen and every
   *  module list. Pass "" for no filter. Pickers keep using list(). */
  listPage: (q: string, page: number, size = 25) =>
    api<Page<Company>>(`/api/v1/companies/page?q=${encodeURIComponent(q)}&page=${page}&size=${size}`),
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
