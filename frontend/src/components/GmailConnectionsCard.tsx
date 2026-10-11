import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { gmailApi, type GmailConnection } from "../api/gmail";
import { ApiError } from "../lib/apiClient";

const queryKey = ["gmail-connections"];

export function GmailConnectionsCard() {
  const { t, i18n } = useTranslation();
  const qc = useQueryClient();
  const connections = useQuery({ queryKey, queryFn: gmailApi.list });
  const [displayName, setDisplayName] = useState("");
  const [mailboxAddress, setMailboxAddress] = useState("");
  const [error, setError] = useState<string | null>(null);
  const refresh = () => { void qc.invalidateQueries({ queryKey }); setError(null); };
  const message = (e: unknown) => e instanceof ApiError ? e.message : "Acțiunea nu a reușit.";

  const start = useMutation({
    mutationFn: (connection: GmailConnection) => gmailApi.startOAuth(connection.id),
    onSuccess: (result, connection) => {
      window.localStorage.setItem("myfinance.gmail.oauth.connection", connection.id);
      window.location.assign(result.authorizationUrl);
    },
    onError: (e) => setError(message(e)),
  });
  const create = useMutation({
    mutationFn: () => gmailApi.create({ displayName, mailboxAddress }),
    onSuccess: (connection) => {
      setDisplayName("");
      setMailboxAddress("");
      refresh();
      start.mutate(connection);
    },
    onError: (e) => setError(message(e)),
  });
  const sync = useMutation({
    mutationFn: (id: string) => gmailApi.sync(id),
    onSuccess: refresh,
    onError: (e) => setError(message(e)),
  });
  const backfill = useMutation({
    mutationFn: (id: string) => gmailApi.backfill(id),
    onSuccess: refresh,
    onError: (e) => setError(message(e)),
  });
  const disconnect = useMutation({
    mutationFn: (id: string) => gmailApi.disconnect(id),
    onSuccess: refresh,
    onError: (e) => setError(message(e)),
  });
  const statusLabel = (status: string) => t(`gmail.status.${status}`, { defaultValue: status });
  const date = (value: string | null) => value
    ? new Date(value).toLocaleString(i18n.language === "ro" ? "ro-RO" : "en-US")
    : t("gmail.never");

  return (
    <div className="card" style={{ display: "grid", gap: 12 }}>
      <div>
        <h3 style={{ margin: 0, fontSize: 15 }}>{t("gmail.title")}</h3>
        <p style={{ color: "var(--text-muted)", fontSize: 12.5, margin: "4px 0 0" }}>
          {t("gmail.description")}
        </p>
      </div>
      {error && <div style={{ color: "#b91c1c", fontSize: 12.5 }}>{error}</div>}
      {(connections.data ?? []).map((connection) => (
        <div key={connection.id} style={{ borderTop: "1px solid var(--hair)", paddingTop: 10 }}>
          <div style={{ display: "flex", justifyContent: "space-between", gap: 10 }}>
            <div>
              <strong>{connection.displayName}</strong>
              <div style={{ color: "var(--text-muted)", fontSize: 12 }}>{connection.mailboxAddress}</div>
              <div style={{ color: "var(--text-muted)", fontSize: 11.5 }}>
                {statusLabel(connection.status)} · {connection.lastResult ?? t("gmail.never")}
              </div>
              <div style={{ color: "var(--text-muted)", fontSize: 11.5 }}>
                {t("gmail.lastSync")}: {date(connection.lastSyncedAt)}
              </div>
            </div>
            <div style={{ display: "flex", gap: 6, alignItems: "start" }}>
              <button className="primary" onClick={() => start.mutate(connection)} disabled={start.isPending}>
                {connection.status === "ACTIVE" ? t("gmail.reauthorize") : t("gmail.connect")}
              </button>
              <button onClick={() => sync.mutate(connection.id)}
                disabled={sync.isPending || connection.status === "DISCONNECTED"}>
                {sync.isPending ? t("gmail.syncing") : t("gmail.sync")}
              </button>
              {(connection.status === "NEEDS_REVIEW" || connection.status === "EXPIRED") && (
                <button onClick={() => backfill.mutate(connection.id)}
                  disabled={backfill.isPending}>
                  {backfill.isPending ? t("gmail.backfilling") : t("gmail.backfill")}
                </button>
              )}
              <button onClick={() => {
                if (window.confirm(t("gmail.disconnectConfirm"))) disconnect.mutate(connection.id);
              }} disabled={disconnect.isPending}>
                {t("gmail.disconnect")}
              </button>
            </div>
          </div>
        </div>
      ))}
      <form style={{ display: "grid", gap: 8, borderTop: "1px solid var(--hair)", paddingTop: 10 }}
        onSubmit={(event) => { event.preventDefault(); create.mutate(); }}>
        <strong style={{ fontSize: 13 }}>{t("gmail.add")}</strong>
        <input required placeholder="Nume conexiune" value={displayName}
          onChange={(event) => setDisplayName(event.target.value)} />
        <input required type="email" placeholder="documente@firma.ro" value={mailboxAddress}
          onChange={(event) => setMailboxAddress(event.target.value)} />
        <button className="primary" type="submit" disabled={create.isPending}>
          {create.isPending ? t("gmail.creating") : t("gmail.create")}
        </button>
      </form>
    </div>
  );
}
