import { api } from "../lib/apiClient";

export type StaffRole = "TENANT_ADMIN" | "EMPLOYEE";
export type UserStatus = "ACTIVE" | "INACTIVE" | "INVITED";

export interface User {
  id: string;
  email: string;
  name: string | null;
  role: "TENANT_ADMIN" | "EMPLOYEE" | "REPRESENTATIVE" | "SUPER_ADMIN";
  status: UserStatus;
  mfaEnabled: boolean;
}

export const usersApi = {
  list: () => api<User[]>("/api/v1/users"),
  invite: (input: { email: string; name: string; role: StaffRole }) =>
    api<User>("/api/v1/users", { method: "POST", body: JSON.stringify(input) }),
  setRole: (id: string, role: StaffRole) =>
    api<User>(`/api/v1/users/${id}/role`, { method: "PUT", body: JSON.stringify({ role }) }),
  deactivate: (id: string) => api<User>(`/api/v1/users/${id}/deactivate`, { method: "POST" }),
  activate: (id: string) => api<User>(`/api/v1/users/${id}/activate`, { method: "POST" }),
};
