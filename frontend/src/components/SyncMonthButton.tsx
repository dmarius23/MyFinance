import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ingestionApi, type SyncStatus } from "../api/ingestion";
import { syncScopeLabel } from "../lib/syncStatus";
import { ApiError } from "../lib/apiClient";
import { useToast } from "./Toast";
import { useSyncTracker } from "./SyncTracker";

/** Module label for the sync toast, keyed by the module type. */
const MODULE_NAV: Record<string, string> = { PAYROLL: "nav.payroll", DECLARATION: "nav.taxes", TRIAL_BALANCE: "nav.reports" };

/**
 * Bulk "sync the whole month from Drive" for one module (PAYROLL / DECLARATION / TRIAL_BALANCE) across ALL
 * companies. Renders only when the module is Drive-sourced. Shows when it was last synced for this month;
 * the in-progress state is surfaced by the shared {@link useSyncTracker} toast (module + month).
 */
export function SyncMonthButton({ type, period, onDone }: { type: string; period: string; onDone?: () => void }) {
  const { t, i18n } = useTranslation();
  const qc = useQueryClient();
  const { toast } = useToast();
  const { track } = useSyncTracker();
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
  const monthLabel = new Date(period).toLocaleDateString(i18n.language === "ro" ? "ro-RO" : "en-US",
    { month: "long", year: "numeric" });
  const trackLabel = `${t(MODULE_NAV[type] ?? "nav.dataSources")} · ${scopeLabel ?? monthLabel}`;
  const sync = useMutation({
    // Fire-and-forget: the sync runs in the background. The SyncTracker lists it (module + month) in a
    // shared toast that stays until every running sync finishes, then shows the results.
    mutationFn: () => ingestionApi.syncMonth({ period, type }),
    onSuccess: () => { onDone?.(); track(type, period, trackLabel); },
    onError: (err: unknown) =>
      toast(err instanceof ApiError && err.status === 409 ? t("ingest.syncBusy") : t("ingest.syncFailed"), "error"),
    onSettled: () => qc.invalidateQueries({ queryKey: ["ingestion-sync-status", type, period] }),
  });

  const st: SyncStatus | undefined = status.data;
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
