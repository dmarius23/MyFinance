import { api } from "../lib/apiClient";

export type TaskStatus = "TODO" | "IN_PROGRESS" | "DONE";

export interface Task {
  id: string;
  title: string;
  details: string | null;
  assigneeId: string | null;
  assigneeName: string | null;
  companyId: string | null;
  companyName: string | null;
  dueDate: string | null;
  status: TaskStatus;
  overdue: boolean;
  createdAt: string;
}

export interface TaskInput {
  title: string;
  details?: string | null;
  assigneeId?: string | null;
  companyId?: string | null;
  dueDate?: string | null;
  status?: TaskStatus;
}

export interface StaffMember {
  id: string;
  name: string;
  role: string;
}

export const tasksApi = {
  list: () => api<Task[]>("/api/v1/tasks"),
  create: (input: TaskInput) => api<Task>("/api/v1/tasks", { method: "POST", body: JSON.stringify(input) }),
  update: (id: string, input: TaskInput) => api<Task>(`/api/v1/tasks/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  changeStatus: (id: string, status: TaskStatus) =>
    api<Task>(`/api/v1/tasks/${id}/status`, { method: "PATCH", body: JSON.stringify({ status }) }),
  remove: (id: string) => api<void>(`/api/v1/tasks/${id}`, { method: "DELETE" }),
};

export const staffApi = {
  list: () => api<StaffMember[]>("/api/v1/staff"),
};
