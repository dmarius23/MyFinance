import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  treasurySyncApi,
  type SyncChange,
  type SyncRunStatus,
  type TreasurySyncItem,
  type TreasurySyncRun,
} from "../api/treasurySyncAdmin";
import { ApiError } from "../lib/apiClient";
import { Field } from "../components/Field";

const badge: React.CSSProperties = {
  fontSize: 11,
  padding: "2px 8px",
  borderRadius: 999,
  whiteSpace: "nowrap",
  display: "inline-block",
};

const STATUS_STYLE: Record<SyncRunStatus, React.CSSProperties> = {
  RUNNING: { background: "#dbeafe", color: "#1e40af", border: "1px solid #bfdbfe" },
  READY_FOR_REVIEW: { background: "#fef3c7", color: "#92400e", border: "1px solid #fde68a" },
  APPLIED: { background: "#ecfdf5", color: "#059669", border: "1px solid #a7f3d0" },
  FAILED: { background: "#fee2e2", color: "#991b1b", border: "1px solid #fecaca" },
  CANCELLED: { background: "#f3f4f6", color: "#6b7280", border: "1px solid #e5e7eb" },
};

const CHANGE_STYLE: Record<SyncChange, React.CSSProperties> = {
  ADDED: { background: "#ecfdf5", color: "#059669", border: "1px solid #a7f3d0" },
  CHANGED: { background: "#fef3c7", color: "#92400e", border: "1px solid #fde68a" },
  UNCHANGED: { background: "#f3f4f6", color: "#6b7280", border: "1px solid #e5e7eb" },
  ERROR: { background: "#fee2e2", color: "#991b1b", border: "1px solid #fecaca" },
};

const CHANGE_FILTERS: (SyncChange | "ALL")[] = ["ALL", "ADDED", "CHANGED", "UNCHANGED", "ERROR"];
const mono: React.CSSProperties = { fontFamily: "var(--mono)", fontSize: 12 };

function fmt(ts: string | null): string {
  return ts ? new Date(ts).toLocaleString() : "—";
}

/**
 * SUPER_ADMIN screen for the ANAF treasury-IBAN sync: start a run (the crawl runs server-side), watch it
 * reach review, inspect the diff against the live reference data, then apply or discard it. Applying writes
 * the effective-dated treasury rows every tenant reads.
 */
export function TreasurySyncAdmin() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [effectiveFrom, setEffectiveFrom] = useState("");
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [changeFilter, setChangeFilter] = useState<SyncChange | "ALL">("ALL");
  const [error, setError] = useState<string | null>(null);

  const runsQuery = useQuery({
    queryKey: ["treasury-sync-runs"],
    queryFn: treasurySyncApi.list,
    // Poll while a crawl is in flight so the status flips to READY_FOR_REVIEW without a manual refresh.
    refetchInterval: (query) =>
      (query.state.data ?? []).some((r) => r.status === "RUNNING") ? 3000 : false,
  });

  const runs = runsQuery.data ?? [];
  const selectedRun = runs.find((r) => r.id === selectedRunId) ?? null;

  const itemsQuery = useQuery({
    queryKey: ["treasury-sync-items", selectedRunId, changeFilter],
    queryFn: () =>
      treasurySyncApi.items(selectedRunId as string, changeFilter === "ALL" ? undefined : [changeFilter]),
    enabled: selectedRunId !== null,
    refetchInterval: selectedRun?.status === "RUNNING" ? 3000 : false,
  });

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ["treasury-sync-runs"] });
    void qc.invalidateQueries({ queryKey: ["treasury-sync-items"] });
  };
  const fail = (e: unknown) => setError(e instanceof ApiError ? e.message : t("treasurySync.failed"));

  const start = useMutation({
    mutationFn: () => treasurySyncApi.start(effectiveFrom || undefined),
    onSuccess: (run) => {
      setSelectedRunId(run.id);
      setError(null);
      invalidate();
    },
    onError: fail,
  });

  const apply = useMutation({
    mutationFn: (runId: string) => treasurySyncApi.apply(runId),
    onSuccess: () => {
      setError(null);
      invalidate();
    },
    onError: fail,
  });

  const cancel = useMutation({
    mutationFn: (runId: string) => treasurySyncApi.cancel(runId),
    onSuccess: () => {
      setError(null);
      invalidate();
    },
    onError: fail,
  });

  const busy = apply.isPending || cancel.isPending;

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="card">
        <h1 style={{ marginTop: 0 }}>{t("treasurySync.title")}</h1>
        <p style={{ color: "var(--text-muted)" }}>{t("treasurySync.intro")}</p>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            setError(null);
            start.mutate();
          }}
          style={{ display: "flex", alignItems: "flex-end", gap: 16, flexWrap: "wrap" }}
        >
          <Field label={t("treasurySync.effectiveFrom")}>
            <input
              type="date"
              value={effectiveFrom}
              onChange={(e) => setEffectiveFrom(e.target.value)}
              style={{ maxWidth: 170 }}
            />
          </Field>
          <button className="primary" type="submit" disabled={start.isPending} style={{ marginBottom: 10 }}>
            {start.isPending ? t("treasurySync.starting") : t("treasurySync.start")}
          </button>
        </form>
        {error && <p style={{ color: "#dc2626", marginBottom: 0 }}>{error}</p>}
      </div>

      <div className="card">
        <h2 style={{ marginTop: 0 }}>{t("treasurySync.runs")}</h2>
        {runsQuery.isLoading ? (
          <p>{t("common.loading")}</p>
        ) : runs.length === 0 ? (
          <p style={{ color: "var(--text-muted)" }}>{t("treasurySync.noRuns")}</p>
        ) : (
          <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
              <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
                <th style={{ padding: 8 }}>{t("treasurySync.col.started")}</th>
                <th style={{ padding: 8 }}>{t("treasurySync.effectiveFrom")}</th>
                <th style={{ padding: 8 }}>{t("treasurySync.col.status")}</th>
                <th style={{ padding: 8 }}>{t("treasurySync.col.treasuries")}</th>
                <th style={{ padding: 8 }} />
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => (
                <RunRow
                  key={run.id}
                  run={run}
                  selected={run.id === selectedRunId}
                  busy={busy}
                  onView={() => setSelectedRunId(run.id)}
                  onApply={() => apply.mutate(run.id)}
                  onCancel={() => cancel.mutate(run.id)}
                />
              ))}
            </tbody>
          </table>
        )}
      </div>

      {selectedRun && (
        <RunDetail
          run={selectedRun}
          items={itemsQuery.data ?? []}
          loading={itemsQuery.isLoading}
          filter={changeFilter}
          onFilter={setChangeFilter}
          busy={busy}
          onApply={() => apply.mutate(selectedRun.id)}
          onCancel={() => cancel.mutate(selectedRun.id)}
        />
      )}
    </div>
  );
}

function StatusBadge({ status }: { status: SyncRunStatus }) {
  const { t } = useTranslation();
  return <span style={{ ...badge, ...STATUS_STYLE[status] }}>{t(`treasurySync.status.${status}`)}</span>;
}

function RunRow({
  run,
  selected,
  busy,
  onView,
  onApply,
  onCancel,
}: {
  run: TreasurySyncRun;
  selected: boolean;
  busy: boolean;
  onView: () => void;
  onApply: () => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const reviewable = run.status === "READY_FOR_REVIEW";
  return (
    <tr style={{ borderTop: "1px solid var(--border)", background: selected ? "var(--surface-2, #f8fafc)" : undefined }}>
      <td style={{ padding: 8, whiteSpace: "nowrap" }}>{fmt(run.startedAt)}</td>
      <td style={{ padding: 8, whiteSpace: "nowrap" }}>{run.effectiveFrom}</td>
      <td style={{ padding: 8 }}><StatusBadge status={run.status} /></td>
      <td style={{ padding: 8, whiteSpace: "nowrap" }}>
        {run.parsedOk}/{run.treasuriesTotal}
        {run.parseFailed > 0 && (
          <span style={{ color: "#991b1b" }}> · {t("treasurySync.failedCount", { count: run.parseFailed })}</span>
        )}
      </td>
      <td style={{ padding: 8, whiteSpace: "nowrap", textAlign: "right" }}>
        <button onClick={onView}>{t("treasurySync.view")}</button>
        {reviewable && (
          <>
            <button className="primary" onClick={onApply} disabled={busy} style={{ marginLeft: 8 }}>
              {t("treasurySync.apply")}
            </button>
            <button onClick={onCancel} disabled={busy} style={{ marginLeft: 8 }}>
              {t("treasurySync.cancel")}
            </button>
          </>
        )}
      </td>
    </tr>
  );
}

function RunDetail({
  run,
  items,
  loading,
  filter,
  onFilter,
  busy,
  onApply,
  onCancel,
}: {
  run: TreasurySyncRun;
  items: TreasurySyncItem[];
  loading: boolean;
  filter: SyncChange | "ALL";
  onFilter: (f: SyncChange | "ALL") => void;
  busy: boolean;
  onApply: () => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const reviewable = run.status === "READY_FOR_REVIEW";
  return (
    <div className="card">
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: 8 }}>
        <h2 style={{ marginTop: 0, marginBottom: 0 }}>
          {t("treasurySync.diff")} <StatusBadge status={run.status} />
        </h2>
        {reviewable && (
          <div>
            <button className="primary" onClick={onApply} disabled={busy}>
              {t("treasurySync.applyAll")}
            </button>
            <button onClick={onCancel} disabled={busy} style={{ marginLeft: 8 }}>
              {t("treasurySync.cancel")}
            </button>
          </div>
        )}
      </div>
      {run.notes && <p style={{ color: "#991b1b" }}>{run.notes}</p>}

      <div style={{ display: "flex", gap: 6, flexWrap: "wrap", margin: "12px 0" }}>
        {CHANGE_FILTERS.map((f) => (
          <button
            key={f}
            onClick={() => onFilter(f)}
            className={filter === f ? "primary" : undefined}
            style={{ fontSize: 12 }}
          >
            {f === "ALL" ? t("treasurySync.filter.all") : t(`treasurySync.change.${f}`)}
          </button>
        ))}
      </div>

      {loading ? (
        <p>{t("common.loading")}</p>
      ) : items.length === 0 ? (
        <p style={{ color: "var(--text-muted)" }}>{t("treasurySync.noItems")}</p>
      ) : (
        <div style={{ overflowX: "auto" }}>
          <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
              <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
                <th style={{ padding: 8 }}>{t("treasurySync.col.change")}</th>
                <th style={{ padding: 8 }}>{t("treasurySync.col.county")}</th>
                <th style={{ padding: 8 }}>{t("treasurySync.col.residence")}</th>
                <th style={{ padding: 8 }}>{t("treasurySync.col.cont5503")}</th>
                <th style={{ padding: 8 }}>CAM</th>
                <th style={{ padding: 8 }}>{t("treasurySync.col.tvaIntern")}</th>
                <th style={{ padding: 8 }}>{t("treasurySync.col.tvaExtern")}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id} style={{ borderTop: "1px solid var(--border)" }}>
                  <td style={{ padding: 8 }}>
                    <span style={{ ...badge, ...CHANGE_STYLE[item.change] }}>
                      {t(`treasurySync.change.${item.change}`)}
                    </span>
                  </td>
                  <td style={{ padding: 8, whiteSpace: "nowrap" }}>{item.county ?? "—"}</td>
                  <td style={{ padding: 8, whiteSpace: "nowrap" }}>
                    {item.change === "ERROR" ? (
                      <span style={{ color: "#991b1b" }} title={item.error ?? undefined}>
                        {item.error ?? t("treasurySync.parseError")}
                      </span>
                    ) : (
                      item.residence ?? "—"
                    )}
                  </td>
                  <td style={{ padding: 8, ...mono }}>{item.iban5503 ?? "—"}</td>
                  <td style={{ padding: 8, ...mono }}>{item.ibanCam ?? "—"}</td>
                  <td style={{ padding: 8, ...mono }}>{item.ibanTvaIntern ?? "—"}</td>
                  <td style={{ padding: 8, ...mono }}>{item.ibanTvaExtern ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
