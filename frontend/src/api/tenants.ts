import { api } from "../lib/apiClient";

/** SUPER_ADMIN tenant (accounting firm) administration — MOD-01. */

export type TenantStatus = "ACTIVE" | "SUSPENDED" | "ARCHIVED";
export const TENANT_STATUSES: TenantStatus[] = ["ACTIVE", "SUSPENDED", "ARCHIVED"];

export interface Tenant {
  id: string;
  name: string;
  cui: string | null;
  status: TenantStatus;
  plan: string;
  createdAt: string; // ISO timestamp
}

export const tenantsApi = {
  list: () => api<Tenant[]>("/api/v1/admin/tenants"),
  create: (input: { name: string; cui?: string | null; plan?: string | null }) =>
    api<Tenant>("/api/v1/admin/tenants", {
      method: "POST",
      body: JSON.stringify(input),
    }),
  changeStatus: (id: string, status: TenantStatus) =>
    api<Tenant>(`/api/v1/admin/tenants/${id}/status`, {
      method: "PATCH",
      body: JSON.stringify({ status }),
    }),
};
