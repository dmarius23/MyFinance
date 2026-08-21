import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ingestionApi, type SyncStatus } from "../api/ingestion";
import { syncScopeLabel } from "../lib/syncStatus";
import { ApiError } from "../lib/apiClient";

/**
 * Bulk "sync the whole month from Drive" for one module (PAYROLL / DECLARATION / TRIAL_BALANCE) across ALL
 * companies. Renders only when the module is Drive-sourced. Shows when it was last synced for this month
 * and — because the state is shared server-side — an in-progress sync started by ANY user, polled live.
 */
export function SyncMonthButton({ type, period, onDone }: { type: string; period: string; onDone?: () => void }) {
  const { t, i18n } = useTranslation();
  const qc = useQueryClient();
  const source = useQuery({ queryKey: ["ingestion-source", type], queryFn: () => ingestionApi.source(type) });
  const status = useQuery({
    queryKey: ["ingestion-sync-status", type, period],
    queryFn: () => ingestionApi.syncStatus(type, period),
    enabled: !!source.data?.driveEnabled,
    // Poll fast while a sync is running (anyone's), slower otherwise to pick up a sync another user starts.
    refetchInterval: (q) => (q.state.data?.running ? 3000 : 20000),
  });
  const sync = useMutation({
    // Fire-and-forget: the sync runs in the background. We don't block for counts — the shared status
    // (polled below) flips to running, then to the finished result, visible to every user.
    mutationFn: () => ingestionApi.syncMonth({ period, type }),
    onSuccess: () => { onDone?.(); },
    onError: (err: unknown) =>
      window.alert(err instanceof ApiError && err.status === 409 ? t("ingest.syncBusy") : t("ingest.syncFailed")),
    onSettled: () => qc.invalidateQueries({ queryKey: ["ingestion-sync-status", type, period] }),
  });

  if (!source.data?.driveEnabled) return null;

  const st: SyncStatus | undefined = status.data;
  const running = !!st?.running || sync.isPending;
  const scope = syncScopeLabel(type, period, i18n.language); // "T2 2026" for balances, else null
  const prefix = scope ? `${scope} · ` : "";
  const fmt = (iso: string) =>
    new Date(iso).toLocaleString(i18n.language === "ro" ? "ro-RO" : "en-US",
      { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });

  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "flex-end", gap: 4 }}>
      <button
        onClick={() => sync.mutate()}
        disabled={running}
        title={t("ingest.syncMonthHint")}
        style={{ background: "var(--chrome-active)", color: "var(--chrome-text)", border: "1px solid #2a3a37" }}
      >
        {running ? t("ingest.syncing") : t("ingest.syncMonth")}
      </button>
      <div style={{ fontSize: 11, color: "var(--text-secondary)", textAlign: "right", lineHeight: 1.3, maxWidth: 260 }}>
        {running ? (
          <span style={{ color: "var(--primary)" }}>
            {prefix}{st?.startedBy ? t("ingest.syncingBy", { name: st.startedBy }) : t("ingest.syncing")}
          </span>
        ) : st?.lastSyncedAt ? (
          <span title={st.lastResult ?? undefined}>{prefix}{t("ingest.lastSynced")}: {fmt(st.lastSyncedAt)}</span>
        ) : (
          <>{prefix}{t("ingest.lastSynced")}: {t("ingest.never")}</>
        )}
      </div>
    </div>
  );
}
