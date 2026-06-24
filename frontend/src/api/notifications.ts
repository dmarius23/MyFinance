import { api } from "../lib/apiClient";

export interface AppNotification {
  id: string;
  type: string;
  title: string;
  body: string;
  companyId: string | null;
  companyName: string | null;
  documentId: string | null;
  readAt: string | null;
  createdAt: string;
}

export const notificationsApi = {
  list: () => api<AppNotification[]>("/api/v1/notifications"),
  unreadCount: () => api<{ count: number }>("/api/v1/notifications/unread-count"),
  markRead: (id: string) => api<void>(`/api/v1/notifications/${id}/read`, { method: "POST" }),
  markAllRead: () => api<void>("/api/v1/notifications/read-all", { method: "POST" }),
};
