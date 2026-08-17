import { useTranslation } from "react-i18next";
import { useMutation, useQuery } from "@tanstack/react-query";
import { ingestionApi, type SyncResult } from "../api/ingestion";

/**
 * Bulk "sync the whole month from Drive" for one module (PAYROLL / DECLARATION / TRIAL_BALANCE), across
 * ALL companies. Renders only when the module is actually Drive-sourced. Mirrors the per-company sync in
 * DocumentManagerModal, but pulls the entire month folder in one pass.
 */
export function SyncMonthButton({ type, period, onDone }: { type: string; period: string; onDone?: () => void }) {
  const { t } = useTranslation();
  const source = useQuery({ queryKey: ["ingestion-source", type], queryFn: () => ingestionApi.source(type) });
  const sync = useMutation({
    mutationFn: () => ingestionApi.syncMonth({ period, type }),
    onSuccess: (r: SyncResult) => {
      onDone?.();
      window.alert(t("ingest.syncDone", r as unknown as Record<string, number>));
    },
    onError: () => window.alert(t("ingest.syncFailed")),
  });

  if (!source.data?.driveEnabled) return null;

  return (
    <button
      onClick={() => sync.mutate()}
      disabled={sync.isPending}
      title={t("ingest.syncMonthHint")}
      style={{ background: "var(--chrome-active)", color: "var(--chrome-text)", border: "1px solid #2a3a37" }}
    >
      {sync.isPending ? t("ingest.syncing") : t("ingest.syncMonth")}
    </button>
  );
}
