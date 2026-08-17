import type { CSSProperties } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { ingestionApi, type SyncStatus } from "../api/ingestion";

/**
 * The shared per (module + month) Drive-sync state, polled live. Used by the per-company upload modals so
 * their "Sync" button is disabled while a month-wide sync (all companies) for that module/month is running
 * — and to show when it was last synced. Same query key as SyncMonthButton, so they share the cache.
 */
export function useSyncStatus(type: string, period: string, enabled: boolean) {
  const q = useQuery({
    queryKey: ["ingestion-sync-status", type, period],
    queryFn: () => ingestionApi.syncStatus(type, period),
    enabled,
    refetchInterval: (query) => (query.state.data?.running ? 3000 : 20000),
  });
  return { status: q.data, running: !!q.data?.running };
}

/** One line of text: "Syncing… (by X)" while a sync runs, else "Last synced: <time>" / "Never". */
export function SyncStatusLine({ status, style }: { status?: SyncStatus; style?: CSSProperties }) {
  const { t, i18n } = useTranslation();
  const base: CSSProperties = { fontSize: 11, textAlign: "center", ...style };
  if (!status) {
    return null;
  }
  if (status.running) {
    return (
      <div style={{ ...base, color: "var(--primary)" }}>
        {status.startedBy ? t("ingest.syncingBy", { name: status.startedBy }) : t("ingest.syncing")}
      </div>
    );
  }
  const when = status.lastSyncedAt
    ? new Date(status.lastSyncedAt).toLocaleString(i18n.language === "ro" ? "ro-RO" : "en-US",
        { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" })
    : t("ingest.never");
  return <div style={base}>{t("ingest.lastSynced")}: {when}</div>;
}
