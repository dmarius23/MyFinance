import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { notificationsApi, type AppNotification } from "../api/notifications";
import { Icon } from "../components/Icon";

const dt = (iso: string) => new Date(iso).toLocaleString("ro-RO", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });

/** MOD-09 — recent activity feed (in-app notifications) for firm staff. */
export function Notifications() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ["notif-list"], queryFn: notificationsApi.list });
  const invalidate = () => { void qc.invalidateQueries({ queryKey: ["notif-list"] }); void qc.invalidateQueries({ queryKey: ["notif-count"] }); };
  const markRead = useMutation({ mutationFn: (id: string) => notificationsApi.markRead(id), onSuccess: invalidate });
  const markAll = useMutation({ mutationFn: notificationsApi.markAllRead, onSuccess: invalidate });

  const open = (n: AppNotification) => { if (!n.readAt) markRead.mutate(n.id); if (n.companyId) nav(`/companies/${n.companyId}`); };
  const rows = list.data ?? [];
  const anyUnread = rows.some((n) => !n.readAt);

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end" }}>
        <div>
          <div style={{ color: "var(--text-secondary)", fontSize: 12.5 }}>{t("notif.crumb")}</div>
          <h2 style={{ margin: "2px 0 0", fontSize: 21, letterSpacing: "-0.01em" }}>{t("notif.title")}</h2>
        </div>
        {anyUnread && <button onClick={() => markAll.mutate()}>{t("notif.markAll")}</button>}
      </div>

      <div className="card" style={{ padding: 0, overflow: "hidden" }}>
        {list.isLoading && <div style={{ padding: 16, color: "var(--text-muted)" }}>{t("common.loading")}</div>}
        {!list.isLoading && rows.length === 0 && <div style={{ padding: 24, textAlign: "center", color: "var(--text-muted)" }}>{t("notif.empty")}</div>}
        {rows.map((n) => (
          <div key={n.id} onClick={() => open(n)}
            style={{ display: "flex", gap: 12, padding: "12px 16px", borderTop: "1px solid var(--hair)", cursor: "pointer", background: n.readAt ? undefined : "var(--row-active)" }}>
            <div style={{ width: 32, height: 32, borderRadius: 8, background: "var(--teal-chip-bg)", display: "grid", placeItems: "center", flexShrink: 0 }}>
              <Icon name="upload" size={15} style={{ color: "var(--teal-chip-fg)" }} />
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
                {!n.readAt && <span style={{ width: 7, height: 7, borderRadius: "50%", background: "var(--primary)" }} />}
                <span style={{ fontWeight: 600, fontSize: 13.5 }}>{n.title}</span>
              </div>
              <div style={{ fontSize: 12.5, color: "var(--text-secondary)" }}>{n.body}</div>
            </div>
            <div className="mono" style={{ fontSize: 11.5, color: "var(--text-muted)", whiteSpace: "nowrap" }}>{dt(n.createdAt)}</div>
          </div>
        ))}
      </div>
    </div>
  );
}
