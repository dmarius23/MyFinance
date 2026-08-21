import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { ingestionApi } from "../api/ingestion";

/**
 * Tracks in-progress Drive module-month syncs and surfaces them as ONE aggregated toast: each running
 * sync is listed by module + month; syncs the user starts meanwhile are added; the "syncing" toast stays
 * until they ALL finish, then a single "finished" toast (with each result) is shown. Polling shares the
 * same query key as {@code SyncMonthButton}, so it keeps working even after the user leaves the module page.
 */
type Entry = { key: string; type: string; period: string; label: string };
type Finished = { key: string; label: string; result?: string };
type Api = { track: (type: string, period: string, label: string) => void };

const Ctx = createContext<Api | null>(null);
const keyOf = (type: string, period: string) => `${type}:${period}`;

export function useSyncTracker(): Api {
  const c = useContext(Ctx);
  if (!c) throw new Error("useSyncTracker must be used within <SyncTrackerProvider>");
  return c;
}

export function SyncTrackerProvider({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const [active, setActive] = useState<Entry[]>([]);
  const finishedRef = useRef<Finished[]>([]);
  const [finishedToast, setFinishedToast] = useState<Finished[] | null>(null);

  const track = useCallback((type: string, period: string, label: string) => {
    const key = keyOf(type, period);
    finishedRef.current = finishedRef.current.filter((f) => f.key !== key); // re-running clears its old result
    setActive((prev) => (prev.some((e) => e.key === key) ? prev : [...prev, { key, type, period, label }]));
  }, []);

  const finish = useCallback((key: string, label: string, result?: string) => {
    finishedRef.current = [...finishedRef.current.filter((f) => f.key !== key), { key, label, result }];
    setActive((prev) => prev.filter((e) => e.key !== key));
  }, []);

  // When the LAST running sync finishes, show the aggregated "finished" toast, then auto-dismiss.
  useEffect(() => {
    if (active.length === 0 && finishedRef.current.length > 0) {
      setFinishedToast(finishedRef.current.slice());
      finishedRef.current = [];
    }
  }, [active.length]);

  return (
    <Ctx.Provider value={{ track }}>
      {children}
      {active.map((e) => <SyncPoll key={e.key} entry={e} onFinish={finish} />)}
      <style>{"@keyframes mf-spin{to{transform:rotate(360deg)}}@keyframes mf-toast-in{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}"}</style>
      <div style={{ position: "fixed", bottom: 20, right: 20, display: "flex", flexDirection: "column",
        gap: 8, zIndex: 10001, pointerEvents: "none", maxWidth: 380 }}>
        {active.length > 0 && (
          <Card tone="info">
            <div style={titleStyle}><Spinner /> {t("ingest.syncing")}</div>
            <ul style={listStyle}>{active.map((e) => <li key={e.key}>{e.label}</li>)}</ul>
          </Card>
        )}
        {finishedToast && (
          <Card tone="success" onClose={() => setFinishedToast(null)}>
            <div style={titleStyle}>{t("ingest.syncFinished")}</div>
            <ul style={listStyle}>
              {finishedToast.map((f) => <li key={f.key}>{f.label}{f.result ? `: ${f.result}` : ""}</li>)}
            </ul>
          </Card>
        )}
      </div>
    </Ctx.Provider>
  );
}

/** Hidden poller: watches one (module, month) sync status and reports when it finishes. */
function SyncPoll({ entry, onFinish }: { entry: Entry; onFinish: (key: string, label: string, result?: string) => void }) {
  const { data } = useQuery({
    queryKey: ["ingestion-sync-status", entry.type, entry.period],
    queryFn: () => ingestionApi.syncStatus(entry.type, entry.period),
    refetchInterval: (q) => (q.state.data?.running ? 2500 : 4000),
  });
  const sawRunning = useRef(false);
  const polls = useRef(0);
  const done = useRef(false);
  useEffect(() => {
    if (!data || done.current) return;
    polls.current += 1;
    if (data.running) { sawRunning.current = true; return; }
    // running === false → finished if we saw it running, or (fast/already-finished fallback) after a few polls.
    if (sawRunning.current || polls.current >= 3) {
      done.current = true;
      onFinish(entry.key, entry.label, data.lastResult ?? undefined);
    }
  }, [data, entry.key, entry.label, onFinish]);
  return null;
}

function Card({ tone, children, onClose }: { tone: "info" | "success"; children: ReactNode; onClose?: () => void }) {
  const accent = tone === "success" ? "var(--primary, #16a34a)" : "var(--primary, #3b82f6)";
  return (
    <div role="status" aria-live="polite" style={{
      position: "relative", pointerEvents: onClose ? "auto" : "none",
      background: "var(--chrome-bg, #0c1413)", color: "var(--chrome-text, #e8efed)",
      border: "1px solid var(--border, #2a3a37)", borderLeft: `3px solid ${accent}`,
      borderRadius: 8, padding: onClose ? "10px 30px 10px 14px" : "10px 14px", fontSize: 13, lineHeight: 1.4,
      boxShadow: "0 8px 24px rgba(0,0,0,0.28)", animation: "mf-toast-in 160ms ease-out",
    }}>
      {children}
      {onClose && (
        <button onClick={onClose} aria-label="Dismiss" style={{
          position: "absolute", top: 6, right: 8, background: "none", border: "none", cursor: "pointer",
          color: "var(--text-secondary, #9fb0ac)", fontSize: 16, lineHeight: 1, padding: 2,
        }}>×</button>
      )}
    </div>
  );
}

function Spinner() {
  return (
    <span style={{ display: "inline-block", width: 11, height: 11, marginRight: 2, verticalAlign: "-1px",
      border: "2px solid currentColor", borderTopColor: "transparent", borderRadius: "50%",
      animation: "mf-spin 0.7s linear infinite", opacity: 0.85 }} />
  );
}

const titleStyle: React.CSSProperties = { fontWeight: 600, display: "flex", alignItems: "center", gap: 6 };
const listStyle: React.CSSProperties = { margin: "4px 0 0", padding: "0 0 0 2px", listStyle: "none",
  display: "flex", flexDirection: "column", gap: 2, color: "var(--text-secondary, #9fb0ac)" };
