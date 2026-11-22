import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { useLocation, useNavigate } from "react-router-dom";
import { gmailApi, type GmailConnection, type GmailSyncResult } from "../api/gmail";
import { ApiError } from "../lib/apiClient";
import { useSyncTracker } from "./SyncTracker";
import { gmailFinishedNote } from "../lib/gmailStatus";

const queryKey = ["gmail-connections"];

const HIDDEN = new Set(["PENDING"]);
const BUSY = new Set(["SYNCING", "BACKFILLING"]);

function isPendingMailbox(connection: GmailConnection) {
  return connection.mailboxAddress.startsWith("pending+") || HIDDEN.has(connection.status);
}

function statusTone(status: string): { bg: string; fg: string } {
  switch (status) {
    case "ACTIVE":
      return { bg: "#ecfdf5", fg: "#047857" };
    case "UNAUTHORIZED":
    case "FAILED":
    case "EXPIRED":
      return { bg: "#fef2f2", fg: "#b91c1c" };
    case "NEEDS_REVIEW":
      return { bg: "#fffbeb", fg: "#92400e" };
    case "SYNCING":
    case "BACKFILLING":
      return { bg: "#eff6ff", fg: "#1d4ed8" };
    case "DISCONNECTED":
      return { bg: "#f3f4f6", fg: "#4b5563" };
    default:
      return { bg: "#f3f4f6", fg: "#4b5563" };
  }
}

export function GmailConnectionsCard() {
  const { t, i18n } = useTranslation();
  const qc = useQueryClient();
  const { run } = useSyncTracker();
  const location = useLocation();
  const navigate = useNavigate();
  const connections = useQuery({ queryKey, queryFn: gmailApi.list });
  const [error, setError] = useState<string | null>(null);
  const [banner, setBanner] = useState<GmailSyncResult | null>(null);

  const refresh = () => {
    void qc.invalidateQueries({ queryKey });
    setError(null);
  };
  const message = (e: unknown) =>
    e instanceof ApiError ? e.message : t("gmail.actionFailed");

  useEffect(() => {
    const state = location.state as { gmailConnected?: GmailSyncResult } | null;
    if (!state?.gmailConnected) return;
    setBanner(state.gmailConnected);
    navigate("/data-sources", { replace: true, state: {} });
    void qc.invalidateQueries({ queryKey });
  }, [location.state, navigate, qc]);

  const start = useMutation({
    mutationFn: (connection: GmailConnection) => gmailApi.startOAuth(connection.id),
    onSuccess: (result, connection) => {
      window.localStorage.setItem("myfinance.gmail.oauth.connection", connection.id);
      window.location.assign(result.authorizationUrl);
    },
    onError: (e) => setError(message(e)),
  });
  const connect = useMutation({
    mutationFn: gmailApi.startNewOAuth,
    onSuccess: (result) => {
      window.localStorage.setItem("myfinance.gmail.oauth.connection", result.connectionId);
      window.location.assign(result.authorizationUrl);
    },
    onError: (e) => setError(message(e)),
  });
  const sync = useMutation({
    mutationFn: (connection: GmailConnection) =>
      run(
        t("gmail.syncLabel", { mailbox: connection.mailboxAddress }),
        gmailApi.sync(connection.id),
        (r) => gmailFinishedNote(t, r),
      ),
    onSuccess: refresh,
    onError: (e) => setError(message(e)),
  });
  const backfill = useMutation({
    mutationFn: (connection: GmailConnection) =>
      run(
        t("gmail.recoverLabel", { mailbox: connection.mailboxAddress }),
        gmailApi.backfill(connection.id),
        (r) => gmailFinishedNote(t, r),
      ),
    onSuccess: refresh,
    onError: (e) => setError(message(e)),
  });
  const disconnect = useMutation({
    mutationFn: (id: string) => gmailApi.disconnect(id),
    onSuccess: refresh,
    onError: (e) => setError(message(e)),
  });

  const visible = (connections.data ?? []).filter((c) => !isPendingMailbox(c));
  // Master only allows one ACTIVE mailbox; keep Connect / Add hidden while one is live.
  const activeConnection = visible.some((connection) => connection.status === "ACTIVE");
  const date = (value: string | null) =>
    value
      ? new Date(value).toLocaleString(i18n.language === "ro" ? "ro-RO" : "en-US")
      : t("gmail.never");

  const busyAny =
    start.isPending || connect.isPending || sync.isPending || backfill.isPending || disconnect.isPending;

  return (
    <div className="card" style={{ display: "grid", gap: 12 }}>
      <div>
        <h3 style={{ margin: 0, fontSize: 15 }}>{t("gmail.title")}</h3>
        <p style={{ color: "var(--text-muted)", fontSize: 12.5, margin: "4px 0 0" }}>
          {t("gmail.description")}
        </p>
      </div>

      {banner && (
        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            gap: 12,
            alignItems: "start",
            background: "#ecfdf5",
            border: "1px solid #a7f3d0",
            borderRadius: 8,
            padding: "10px 12px",
            fontSize: 12.5,
            color: "#065f46",
          }}
        >
          <div>
            <strong>{t("gmail.connectedTitle")}</strong>
            <div style={{ marginTop: 2 }}>{gmailFinishedNote(t, banner)}</div>
          </div>
          <button
            type="button"
            onClick={() => setBanner(null)}
            aria-label={t("common.close")}
            style={{ background: "none", border: "none", cursor: "pointer", color: "inherit" }}
          >
            ×
          </button>
        </div>
      )}

      {error && <div style={{ color: "#b91c1c", fontSize: 12.5 }}>{error}</div>}

      {visible.length === 0 && !activeConnection && (
        <div style={{ display: "grid", gap: 10 }}>
          <p style={{ margin: 0, color: "var(--text-muted)", fontSize: 13 }}>{t("gmail.empty")}</p>
          <button
            className="primary"
            type="button"
            onClick={() => connect.mutate()}
            disabled={connect.isPending}
            style={{ justifySelf: "start" }}
          >
            {connect.isPending ? t("gmail.connecting") : t("gmail.connect")}
          </button>
        </div>
      )}

      {visible.map((connection) => {
        const tone = statusTone(connection.status);
        const rowBusy = BUSY.has(connection.status) || busyAny;
        return (
          <div key={connection.id} style={{ borderTop: "1px solid var(--hair)", paddingTop: 10 }}>
            <div style={{ display: "flex", justifyContent: "space-between", gap: 10, flexWrap: "wrap" }}>
              <div style={{ minWidth: 0, flex: "1 1 220px" }}>
                <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
                  <strong>{connection.displayName}</strong>
                  <span
                    style={{
                      fontSize: 11,
                      fontWeight: 600,
                      padding: "2px 8px",
                      borderRadius: 999,
                      background: tone.bg,
                      color: tone.fg,
                    }}
                  >
                    {t(`gmail.status.${connection.status}`, { defaultValue: connection.status })}
                  </span>
                </div>
                <div style={{ color: "var(--text-muted)", fontSize: 12 }}>{connection.mailboxAddress}</div>
                {connection.lastResult && (
                  <div style={{ color: "var(--text-muted)", fontSize: 11.5, marginTop: 2 }}>
                    {connection.lastResult}
                  </div>
                )}
                <div style={{ color: "var(--text-muted)", fontSize: 11.5 }}>
                  {t("gmail.lastSync")}: {date(connection.lastSyncedAt)}
                </div>
              </div>
              <div style={{ display: "flex", gap: 6, alignItems: "start", flexWrap: "wrap" }}>
                <ConnectionActions
                  connection={connection}
                  disabled={rowBusy}
                  syncing={sync.isPending}
                  recovering={backfill.isPending}
                  authorizing={start.isPending}
                  onAuthorize={() => start.mutate(connection)}
                  onSync={() => sync.mutate(connection)}
                  onRecover={() => backfill.mutate(connection)}
                  onDisconnect={() => {
                    if (window.confirm(t("gmail.disconnectConfirm"))) disconnect.mutate(connection.id);
                  }}
                  t={t}
                />
              </div>
            </div>
          </div>
        );
      })}

      {visible.length > 0 && !activeConnection && (
        <div style={{ borderTop: "1px solid var(--hair)", paddingTop: 10 }}>
          <button
            className="primary"
            type="button"
            onClick={() => connect.mutate()}
            disabled={connect.isPending}
          >
            {connect.isPending ? t("gmail.connecting") : t("gmail.connect")}
          </button>
        </div>
      )}
    </div>
  );
}

function ConnectionActions({
  connection,
  disabled,
  syncing,
  recovering,
  authorizing,
  onAuthorize,
  onSync,
  onRecover,
  onDisconnect,
  t,
}: {
  connection: GmailConnection;
  disabled: boolean;
  syncing: boolean;
  recovering: boolean;
  authorizing: boolean;
  onAuthorize: () => void;
  onSync: () => void;
  onRecover: () => void;
  onDisconnect: () => void;
  t: (key: string) => string;
}) {
  const status = connection.status;

  if (BUSY.has(status)) {
    return (
      <button type="button" disabled>
        {status === "BACKFILLING" ? t("gmail.backfilling") : t("gmail.syncing")}
      </button>
    );
  }

  if (status === "DISCONNECTED") {
    return (
      <>
        <button className="primary" type="button" disabled={disabled || authorizing} onClick={onAuthorize}>
          {authorizing ? t("gmail.connecting") : t("gmail.reconnect")}
        </button>
        <button type="button" disabled={disabled} onClick={onDisconnect}>
          {t("gmail.remove")}
        </button>
      </>
    );
  }

  if (status === "UNAUTHORIZED") {
    return (
      <>
        <button className="primary" type="button" disabled={disabled || authorizing} onClick={onAuthorize}>
          {authorizing ? t("gmail.connecting") : t("gmail.reauthorize")}
        </button>
        <button type="button" disabled={disabled} onClick={onDisconnect}>
          {t("gmail.disconnect")}
        </button>
      </>
    );
  }

  if (status === "NEEDS_REVIEW" || status === "EXPIRED") {
    return (
      <>
        <button className="primary" type="button" disabled={disabled || recovering} onClick={onRecover}>
          {recovering ? t("gmail.backfilling") : t("gmail.recover")}
        </button>
        <button type="button" disabled={disabled || syncing} onClick={onSync}>
          {syncing ? t("gmail.syncing") : t("gmail.syncNow")}
        </button>
        <button type="button" disabled={disabled || authorizing} onClick={onAuthorize}>
          {t("gmail.reauthorize")}
        </button>
      </>
    );
  }

  if (status === "FAILED") {
    return (
      <>
        <button className="primary" type="button" disabled={disabled || syncing} onClick={onSync}>
          {syncing ? t("gmail.syncing") : t("gmail.retrySync")}
        </button>
        <button type="button" disabled={disabled || authorizing} onClick={onAuthorize}>
          {t("gmail.reauthorize")}
        </button>
        <button type="button" disabled={disabled} onClick={onDisconnect}>
          {t("gmail.disconnect")}
        </button>
      </>
    );
  }

  // ACTIVE and any other healthy-ish state: Sync primary, Recover secondary, then reauth/disconnect.
  return (
    <>
      <button className="primary" type="button" disabled={disabled || syncing} onClick={onSync}>
        {syncing ? t("gmail.syncing") : t("gmail.syncNow")}
      </button>
      <button type="button" disabled={disabled || recovering} onClick={onRecover}>
        {recovering ? t("gmail.backfilling") : t("gmail.recover")}
      </button>
      <button type="button" disabled={disabled || authorizing} onClick={onAuthorize}>
        {t("gmail.reauthorize")}
      </button>
      <button type="button" disabled={disabled} onClick={onDisconnect}>
        {t("gmail.disconnect")}
      </button>
    </>
  );
}
