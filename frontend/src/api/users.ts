import { api } from "../lib/apiClient";

export type StaffRole = "TENANT_ADMIN" | "EMPLOYEE";
/** PENDING_INVITE = provisioned (bulk CSV import) but not yet emailed; INVITED = set-password link sent. */
export type UserStatus = "ACTIVE" | "INACTIVE" | "PENDING_INVITE" | "INVITED";

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
  // On-demand: email this staff member a fresh set-password link (expired link, or never set one).
  sendInvite: (id: string) => api<void>(`/api/v1/users/${id}/invite`, { method: "POST" }),
  setRole: (id: string, role: StaffRole) =>
    api<User>(`/api/v1/users/${id}/role`, { method: "PUT", body: JSON.stringify({ role }) }),
  deactivate: (id: string) => api<User>(`/api/v1/users/${id}/deactivate`, { method: "POST" }),
  activate: (id: string) => api<User>(`/api/v1/users/${id}/activate`, { method: "POST" }),
};
