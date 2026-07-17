import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ingestionApi, type Connection, type SyncResult } from "../api/ingestion";
import { ApiError } from "../lib/apiClient";

/**
 * MOD-15 — admin screen to configure document-source folders (Google Drive) and trigger a sync.
 * The admin shares the Drive folder with the app's service account and pastes the folder id here;
 * payroll files under <company>/<month>/ are pulled into the intake pipeline.
 */
export function DataSources() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const conns = useQuery({ queryKey: ["ingestion-connections"], queryFn: ingestionApi.list });
  const [form, setForm] = useState({ displayName: "", rootFolderId: "", writeEnabled: true });
  const [error, setError] = useState<string | null>(null);
  const [openImports, setOpenImports] = useState<string | null>(null);
  const [edit, setEdit] = useState<{ id: string; displayName: string; rootFolderId: string; writeEnabled: boolean } | null>(null);

  const refresh = () => { void qc.invalidateQueries({ queryKey: ["ingestion-connections"] }); setError(null); };
  const onErr = (e: unknown) => setError(e instanceof ApiError ? e.message : "Action failed");

  const create = useMutation({
    // Connect the Drive root for all documents (auto-classified), read + write.
    mutationFn: () => ingestionApi.create({ provider: "GOOGLE_DRIVE", displayName: form.displayName, rootFolderId: form.rootFolderId, writeEnabled: form.writeEnabled }),
    onSuccess: () => { refresh(); setForm({ displayName: "", rootFolderId: "", writeEnabled: true }); },
    onError: onErr,
  });
  const save = useMutation({
    // Editing a connection clears forcedType (null) → a general root connection that resolves type per sub-folder.
    mutationFn: () => ingestionApi.update(edit!.id, { displayName: edit!.displayName, rootFolderId: edit!.rootFolderId, writeEnabled: edit!.writeEnabled, forcedType: null }),
    onSuccess: () => { refresh(); setEdit(null); },
    onError: onErr,
  });
  const remove = useMutation({ mutationFn: (id: string) => ingestionApi.remove(id), onSuccess: refresh, onError: onErr });
  const sync = useMutation({
    mutationFn: (id: string) => ingestionApi.sync(id),
    onSuccess: (r: SyncResult) => { refresh(); window.alert(t("ingest.syncDone", r as unknown as Record<string, number>)); },
    onError: onErr,
  });
  // Inline write toggle straight from the row (keeps name/root; a general root connection has no forced type).
  const toggleWrite = useMutation({
    mutationFn: (c: Connection) => ingestionApi.update(c.id, { displayName: c.displayName, rootFolderId: c.rootFolderId, writeEnabled: !c.writeEnabled, forcedType: null }),
    onSuccess: refresh,
    onError: onErr,
  });
  const hasConnection = (conns.data ?? []).length > 0;

  return (
    <div style={{ display: "grid", gap: 16, maxWidth: 1100 }}>
      <div>
        <div style={{ color: "var(--text-secondary)", fontSize: 12.5 }}>{t("ingest.crumb")}</div>
        <h2 style={{ margin: "2px 0 0", fontSize: 21 }}>{t("nav.dataSources")}</h2>
        <p style={{ color: "var(--text-muted)", fontSize: 13, marginTop: 6 }}>{t("ingest.intro")}</p>
      </div>

      {error && <div className="card" style={{ color: "#b91c1c", borderColor: "#fecaca", background: "#fef2f2" }}>{error}</div>}

      {/* existing connections */}
      <div className="card" style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ ...row, background: "var(--th-bg)", ...th }}>
          <div>{t("ingest.colSource")}</div><div>{t("ingest.colFolder")}</div>
          <div>{t("ingest.colLast")}</div><div style={{ textAlign: "right" }}>{t("statements.actions")}</div>
        </div>
        {conns.isLoading && <div style={{ padding: 14, color: "var(--text-muted)" }}>…</div>}
        {(conns.data ?? []).length === 0 && !conns.isLoading && (
          <div style={{ padding: 14, color: "var(--text-muted)", fontSize: 13 }}>{t("ingest.none")}</div>
        )}
        {(conns.data ?? []).map((c: Connection) => (
          <div key={c.id}>
            <div style={{ ...row, borderTop: "1px solid var(--hair)" }}>
              <div>
                <div style={{ fontWeight: 600 }}>{c.displayName}</div>
                <div className="mono" style={{ fontSize: 11, color: "var(--text-muted)", marginBottom: 3 }}>{c.provider}</div>
                <label style={{ display: "inline-flex", alignItems: "center", gap: 5, fontSize: 11.5, cursor: toggleWrite.isPending ? "default" : "pointer", color: c.writeEnabled ? "var(--primary-dark)" : "var(--text-muted)" }}>
                  <input type="checkbox" checked={c.writeEnabled} disabled={toggleWrite.isPending} onChange={() => toggleWrite.mutate(c)} />
                  {t("ingest.writeShort")}
                </label>
              </div>
              <div className="mono" style={{ fontSize: 11.5, color: "var(--text-secondary)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{c.rootFolderId}</div>
              <div style={{ fontSize: 12, color: "var(--text-secondary)" }}>
                {c.lastSyncedAt ? new Date(c.lastSyncedAt).toLocaleString() : t("ingest.never")}
                {c.lastResult && <div style={{ fontSize: 11, color: "var(--text-muted)" }}>{c.lastResult}</div>}
              </div>
              <div style={{ display: "flex", gap: 6, justifyContent: "flex-end" }}>
                <button className="primary" disabled={sync.isPending} onClick={() => sync.mutate(c.id)}>{sync.isPending ? "…" : t("ingest.syncNow")}</button>
                <button onClick={() => setEdit(edit?.id === c.id ? null : { id: c.id, displayName: c.displayName, rootFolderId: c.rootFolderId, writeEnabled: c.writeEnabled })}>{t("ingest.edit")}</button>
                <button onClick={() => setOpenImports(openImports === c.id ? null : c.id)}>{t("ingest.imports")}</button>
                <button style={{ color: "#dc2626" }} onClick={() => { if (window.confirm(t("ingest.confirmDelete", { name: c.displayName }))) remove.mutate(c.id); }}>✕</button>
              </div>
            </div>
            {edit?.id === c.id && (
              <form style={{ background: "var(--bg)", padding: "10px 16px 14px", borderTop: "1px solid var(--hair)", display: "grid", gap: 8, maxWidth: 560 }}
                onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
                <input value={edit.displayName} placeholder={t("ingest.fieldName")} required
                  onChange={(e) => setEdit({ ...edit, displayName: e.target.value })} style={input} />
                <input value={edit.rootFolderId} placeholder={t("ingest.fieldFolder")} required
                  onChange={(e) => setEdit({ ...edit, rootFolderId: e.target.value })} style={input} />
                <label style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13, color: "var(--text-secondary)" }}>
                  <input type="checkbox" checked={edit.writeEnabled} onChange={(e) => setEdit({ ...edit, writeEnabled: e.target.checked })} />
                  {t("ingest.writeAccess")}
                </label>
                <div style={{ display: "flex", gap: 8 }}>
                  <button className="primary" type="submit" disabled={save.isPending}>{save.isPending ? "…" : t("common.save")}</button>
                  <button type="button" onClick={() => setEdit(null)}>{t("common.cancel")}</button>
                </div>
              </form>
            )}
            {openImports === c.id && <ImportsPanel connectionId={c.id} />}
          </div>
        ))}
      </div>

      {/* add connection — only when none exists yet (one root connection is enough) */}
      {!hasConnection && (
      <div className="card">
        <h3 style={{ marginTop: 0, fontSize: 15 }}>{t("ingest.addTitle")}</h3>
        <p style={{ color: "var(--text-muted)", fontSize: 12.5, marginTop: 2 }}>{t("ingest.addHint")}</p>
        <form style={{ display: "grid", gap: 8, marginTop: 8 }} onSubmit={(e) => { e.preventDefault(); create.mutate(); }}>
          <input placeholder={t("ingest.fieldName")} required value={form.displayName}
            onChange={(e) => setForm({ ...form, displayName: e.target.value })} style={input} />
          <input placeholder={t("ingest.fieldFolder")} required value={form.rootFolderId}
            onChange={(e) => setForm({ ...form, rootFolderId: e.target.value })} style={input} />
          <label style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13, color: "var(--text-secondary)" }}>
            <input type="checkbox" checked={form.writeEnabled}
              onChange={(e) => setForm({ ...form, writeEnabled: e.target.checked })} />
            {t("ingest.writeAccess")}
          </label>
          <button className="primary" type="submit" disabled={create.isPending} style={{ justifySelf: "start" }}>
            {create.isPending ? "…" : t("ingest.connect")}
          </button>
        </form>
      </div>
      )}
    </div>
  );
}

function ImportsPanel({ connectionId }: { connectionId: string }) {
  const { t } = useTranslation();
  const imports = useQuery({ queryKey: ["ingestion-imports", connectionId], queryFn: () => ingestionApi.imports(connectionId) });
  const tone = (s: string) => s === "IMPORTED" ? "#16a34a" : s === "NEEDS_REVIEW" ? "#92400e" : "#6b7280";
  return (
    <div style={{ background: "var(--bg)", padding: "8px 16px 12px", borderTop: "1px solid var(--hair)" }}>
      {imports.isLoading && <div style={{ color: "var(--text-muted)", fontSize: 12 }}>…</div>}
      {(imports.data ?? []).length === 0 && !imports.isLoading && <div style={{ color: "var(--text-muted)", fontSize: 12.5 }}>{t("ingest.noImports")}</div>}
      {(imports.data ?? []).map((r) => (
        <div key={r.id} style={{ display: "flex", justifyContent: "space-between", gap: 8, padding: "5px 0", fontSize: 12.5 }}>
          <span style={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
            <span className="mono" style={{ color: "var(--text-muted)" }}>{r.sourcePath}/</span>{r.filename}
            {r.detail && <span style={{ color: "#92400e" }}> — {r.detail}</span>}
          </span>
          <span style={{ flex: "none", fontWeight: 700, color: tone(r.status), fontSize: 10.5, textTransform: "uppercase" }}>{r.status}</span>
        </div>
      ))}
    </div>
  );
}

const row: React.CSSProperties = { display: "grid", gridTemplateColumns: "minmax(200px,1.4fr) minmax(150px,1fr) 1fr 340px", alignItems: "center", gap: 12, padding: "10px 16px" };
const th: React.CSSProperties = { fontSize: 9.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "#8a9794" };
const input: React.CSSProperties = { padding: "8px 10px", border: "1px solid var(--border)", borderRadius: 8, fontSize: 13, maxWidth: 520 };
