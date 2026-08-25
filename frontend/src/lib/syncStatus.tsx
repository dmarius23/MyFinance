import type { CSSProperties } from "react";
import type { TFunction } from "i18next";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { ingestionApi, type SyncResult, type SyncStatus } from "../api/ingestion";

/**
 * One-line summary of a finished sync for the shared "finished" toast — counts plus any per-file issues.
 * Shared by every sync entry point so the result reads identically across modules.
 */
export function syncFinishedNote(t: TFunction, r: SyncResult): string {
  const parts = [t("ingest.syncDone", r as unknown as Record<string, number>)];
  for (const iss of r.issues ?? []) parts.push(`⚠ ${iss.filename} — ${iss.reason}`);
  return parts.join(" · ");
}

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

/**
 * Balances (reports) are synced per trimester, so the status is keyed to the quarter — label it (e.g.
 * "T2 2026") so several months of the same quarter don't look like duplicate times. Null for month-based
 * modules (payroll / declarations).
 */
export function syncScopeLabel(type: string, period: string, lang: string): string | null {
  if (type !== "TRIAL_BALANCE") {
    return null;
  }
  const d = new Date(period);
  const q = Math.floor(d.getMonth() / 3) + 1;
  return `${lang === "ro" ? "T" : "Q"}${q} ${d.getFullYear()}`;
}

/** One line: "Syncing… (by X)" while a sync runs, else "Last synced: <time>" / "Never". A {@code scope}
 *  (e.g. "T2 2026") is prefixed so quarterly balance syncs read as the quarter, not the month. */
export function SyncStatusLine({ status, scope, style }: { status?: SyncStatus; scope?: string | null; style?: CSSProperties }) {
  const { t, i18n } = useTranslation();
  const base: CSSProperties = { fontSize: 11, textAlign: "center", ...style };
  const prefix = scope ? `${scope} · ` : "";
  if (!status) {
    return null;
  }
  if (status.running) {
    return (
      <div style={{ ...base, color: "var(--primary)" }}>
        {prefix}{status.startedBy ? t("ingest.syncingBy", { name: status.startedBy }) : t("ingest.syncing")}
      </div>
    );
  }
  const when = status.lastSyncedAt
    ? new Date(status.lastSyncedAt).toLocaleString(i18n.language === "ro" ? "ro-RO" : "en-US",
        { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" })
    : t("ingest.never");
  return <div style={base} title={status.lastResult ?? undefined}>{prefix}{t("ingest.lastSynced")}: {when}</div>;
}
