import { api, upload } from "../lib/apiClient";
import type { Document } from "./documents";

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

/** Representative PWA: upload a document for the rep's own company (triggers staff notification + email). */
export const portalApi = {
  uploadDocument: (file: File, periodMonth?: string) => {
    const form = new FormData();
    form.append("file", file);
    if (periodMonth) form.append("periodMonth", periodMonth);
    return upload<Document>("/api/v1/portal/documents", form);
  },
};
