import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { notificationsApi, type AppNotification } from "../api/notifications";
import { Icon } from "./Icon";

const ago = (iso: string) => {
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return "acum";
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86400) return `${Math.floor(s / 3600)}h`;
  return new Date(iso).toLocaleDateString("ro-RO", { day: "numeric", month: "short" });
};

/** Topbar notification bell: unread badge (polled) + dropdown feed with mark-read. Firm staff only. */
export function NotificationBell() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  const count = useQuery({
    queryKey: ["notif-count"],
    queryFn: notificationsApi.unreadCount,
    refetchInterval: 30000,
    refetchOnWindowFocus: true,
  });
  const list = useQuery({
    queryKey: ["notif-list"],
    queryFn: notificationsApi.list,
    enabled: open,
  });

  const markRead = useMutation({
    mutationFn: (id: string) => notificationsApi.markRead(id),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ["notif-count"] }); void qc.invalidateQueries({ queryKey: ["notif-list"] }); },
  });
  const markAll = useMutation({
    mutationFn: notificationsApi.markAllRead,
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ["notif-count"] }); void qc.invalidateQueries({ queryKey: ["notif-list"] }); },
  });

  useEffect(() => {
    const onDoc = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, []);

  const unread = count.data?.count ?? 0;
  const onItem = (n: AppNotification) => {
    if (!n.readAt) markRead.mutate(n.id);
    setOpen(false);
    if (n.companyId) nav(`/companies/${n.companyId}`);
  };

  return (
    <div ref={ref} style={{ position: "relative" }}>
      <button onClick={() => setOpen((o) => !o)} title={t("notif.title")}
        style={{ position: "relative", background: "none", border: "none", color: "var(--chrome-text)", cursor: "pointer", padding: 4, display: "grid", placeItems: "center" }}>
        <Icon name="bell" size={17} />
        {unread > 0 && (
          <span style={{ position: "absolute", top: 0, right: 0, minWidth: 15, height: 15, padding: "0 3px", borderRadius: 8, background: "var(--danger, #dc2626)", color: "#fff", fontSize: 9.5, fontWeight: 700, display: "grid", placeItems: "center", lineHeight: 1 }}>
            {unread > 9 ? "9+" : unread}
          </span>
        )}
      </button>

      {open && (
        <div style={panel}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "10px 12px", borderBottom: "1px solid var(--hair)" }}>
            <b style={{ fontSize: 13 }}>{t("notif.title")}</b>
            {unread > 0 && <button onClick={() => markAll.mutate()} style={linkBtn}>{t("notif.markAll")}</button>}
          </div>
          <div style={{ maxHeight: 360, overflowY: "auto" }}>
            {list.isLoading && <div style={empty}>{t("common.loading")}</div>}
            {!list.isLoading && (list.data ?? []).length === 0 && <div style={empty}>{t("notif.empty")}</div>}
            {(list.data ?? []).map((n) => (
              <button key={n.id} onClick={() => onItem(n)} style={{ ...item, background: n.readAt ? "transparent" : "var(--row-active)" }}>
                <div style={{ display: "flex", gap: 8 }}>
                  {!n.readAt && <span style={{ width: 7, height: 7, borderRadius: "50%", background: "var(--primary)", marginTop: 5, flexShrink: 0 }} />}
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 12.5, fontWeight: 600 }}>{n.title}</div>
                    <div style={{ fontSize: 12, color: "var(--text-secondary)", overflowWrap: "anywhere", whiteSpace: "pre-line" }}>{n.body}</div>
                    <div style={{ fontSize: 10.5, color: "var(--text-muted)", marginTop: 2 }}>{ago(n.createdAt)}</div>
                  </div>
                </div>
              </button>
            ))}
          </div>
          <button onClick={() => { setOpen(false); nav("/notifications"); }} style={{ ...linkBtn, width: "100%", padding: "9px 12px", borderTop: "1px solid var(--hair)", textAlign: "center" }}>
            {t("notif.viewAll")}
          </button>
        </div>
      )}
    </div>
  );
}

const panel: React.CSSProperties = { position: "absolute", top: "calc(100% + 8px)", right: 0, width: 340, background: "var(--surface)", color: "var(--text-primary)", border: "1px solid var(--border)", borderRadius: 12, boxShadow: "var(--shadow-modal)", zIndex: 80, overflow: "hidden" };
const item: React.CSSProperties = { display: "block", width: "100%", textAlign: "left", border: "none", borderBottom: "1px solid var(--hair)", padding: "10px 12px", cursor: "pointer", font: "inherit" };
const empty: React.CSSProperties = { padding: "20px 12px", textAlign: "center", color: "var(--text-muted)", fontSize: 12.5 };
const linkBtn: React.CSSProperties = { background: "none", border: "none", color: "var(--primary-dark)", cursor: "pointer", fontSize: 12, fontWeight: 600 };
