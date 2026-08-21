import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ingestionApi, type SyncStatus } from "../api/ingestion";
import { syncScopeLabel } from "../lib/syncStatus";
import { ApiError } from "../lib/apiClient";
import { useToast } from "./Toast";

/**
 * Bulk "sync the whole month from Drive" for one module (PAYROLL / DECLARATION / TRIAL_BALANCE) across ALL
 * companies. Renders only when the module is Drive-sourced. Shows when it was last synced for this month
 * and — because the state is shared server-side — an in-progress sync started by ANY user, polled live.
 */
export function SyncMonthButton({ type, period, onDone }: { type: string; period: string; onDone?: () => void }) {
  const { t, i18n } = useTranslation();
  const qc = useQueryClient();
  const { toast } = useToast();
  const source = useQuery({ queryKey: ["ingestion-source", type], queryFn: () => ingestionApi.source(type) });
  const status = useQuery({
    queryKey: ["ingestion-sync-status", type, period],
    queryFn: () => ingestionApi.syncStatus(type, period),
    enabled: !!source.data?.driveEnabled,
    // Poll fast while a sync is running (anyone's), slower otherwise to pick up a sync another user starts.
    refetchInterval: (q) => (q.state.data?.running ? 3000 : 20000),
  });
  const scopeLabel = syncScopeLabel(type, period, i18n.language); // "T2 2026" for balances, else null
  const toastPrefix = scopeLabel ? `${scopeLabel} · ` : "";
  const sync = useMutation({
    // Fire-and-forget: the sync runs in the background. We don't block for counts — the shared status
    // (polled below) flips to running, then to the finished result, surfaced via toasts.
    mutationFn: () => ingestionApi.syncMonth({ period, type }),
    onSuccess: () => { onDone?.(); toast(`${toastPrefix}${t("ingest.syncing")}`, "info"); },
    onError: (err: unknown) =>
      toast(err instanceof ApiError && err.status === 409 ? t("ingest.syncBusy") : t("ingest.syncFailed"), "error"),
    onSettled: () => qc.invalidateQueries({ queryKey: ["ingestion-sync-status", type, period] }),
  });

  // When a running sync (started by anyone) finishes, toast the result once — no persistent on-screen banner.
  const st: SyncStatus | undefined = status.data;
  const wasRunning = useRef<boolean | undefined>(undefined);
  useEffect(() => {
    const running = !!st?.running;
    if (wasRunning.current === true && !running) {
      toast(`${toastPrefix}${t("ingest.syncFinished")}${st?.lastResult ? `: ${st.lastResult}` : ""}`, "success");
    }
    wasRunning.current = running;
  }, [st?.running, st?.lastResult, toastPrefix, t, toast]);

  if (!source.data?.driveEnabled) return null;
  const running = !!st?.running || sync.isPending;
  const fmt = (iso: string) =>
    new Date(iso).toLocaleString(i18n.language === "ro" ? "ro-RO" : "en-US",
      { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });

  // The in-progress "syncing…" message is surfaced via a toast (see above), not an on-screen banner;
  // the sub-label just shows when this module/month was last synced.
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
        {st?.lastSyncedAt ? (
          <span title={st.lastResult ?? undefined}>{toastPrefix}{t("ingest.lastSynced")}: {fmt(st.lastSyncedAt)}</span>
        ) : (
          <>{toastPrefix}{t("ingest.lastSynced")}: {t("ingest.never")}</>
        )}
      </div>
    </div>
  );
}
