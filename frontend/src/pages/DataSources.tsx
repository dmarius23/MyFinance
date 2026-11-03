import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ingestionApi, type DrivePurpose } from "../api/ingestion";
import { settingsApi } from "../api/settings";
import { ApiError } from "../lib/apiClient";
import { GmailConnectionsCard } from "../components/GmailConnectionsCard";
import { EmailProviderSection, WhatsAppProviderSection } from "../components/MessagingSettings";

/**
 * MOD-15 — admin screen to configure document-source folders (Google Drive) and trigger a sync.
 * The admin shares the Drive folder with the app's service account and pastes the folder id here;
 * payroll files under <company>/<month>/ are pulled into the intake pipeline.
 */
export function DataSources() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const conns = useQuery({ queryKey: ["ingestion-connections"], queryFn: ingestionApi.list });
  const [form, setForm] = useState<{ displayName: string; rootFolderId: string; writeEnabled: boolean; purpose: DrivePurpose; rootIsYearFolder: boolean }>({ displayName: "", rootFolderId: "", writeEnabled: true, purpose: "DECLARATIONS", rootIsYearFolder: false });
  const [error, setError] = useState<string | null>(null);

  const refresh = () => { void qc.invalidateQueries({ queryKey: ["ingestion-connections"] }); setError(null); };
  const onErr = (e: unknown) => setError(e instanceof ApiError ? e.message : "Action failed");

  const create = useMutation({
    // Connect the Drive root for all documents (auto-classified), read + write.
    mutationFn: () => ingestionApi.create({ provider: "GOOGLE_DRIVE", displayName: form.displayName, rootFolderId: form.rootFolderId, writeEnabled: form.writeEnabled, purpose: form.purpose, rootIsYearFolder: form.purpose === "DECLARATIONS" && form.rootIsYearFolder }),
    onSuccess: () => { refresh(); setForm({ displayName: "", rootFolderId: "", writeEnabled: true, purpose: "DECLARATIONS", rootIsYearFolder: false }); },
    onError: onErr,
  });
  return (
    <div style={{ display: "grid", gap: 16, maxWidth: 1100 }}>
      <div>
        <div style={{ color: "var(--text-secondary)", fontSize: 12.5 }}>{t("ingest.crumb")}</div>
        <h2 style={{ margin: "2px 0 0", fontSize: 21 }}>{t("nav.dataSources")}</h2>
      </div>

      {error && <div className="card" style={{ color: "#b91c1c", borderColor: "#fecaca", background: "#fef2f2" }}>{error}</div>}

      {/* The page is the firm's settings hub: document sources first, then the messaging providers. */}
      <div>
        <h3 style={{ margin: 0, fontSize: 16 }}>{t("ingest.sourcesHeading")}</h3>
        <p style={{ color: "var(--text-muted)", fontSize: 13, marginTop: 6 }}>{t("ingest.intro")}</p>
      </div>

      <AutoSyncCard />
      <GmailConnectionsCard />

      {/* add connection — a tenant may have up to two: a DECLARATIONS drive and an ACCOUNTING drive */}
      {(conns.data ?? []).length < 2 && (
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
          {form.writeEnabled && (
            <label style={{ display: "grid", gap: 4, fontSize: 12.5, color: "var(--text-secondary)" }}>
              {t("ingest.purposeLabel")}
              <select value={form.purpose} onChange={(e) => setForm({ ...form, purpose: e.target.value as DrivePurpose })} style={input}>
                <option value="DECLARATIONS">{t("ingest.purposeDeclarations")}</option>
                <option value="ACCOUNTING">{t("ingest.purposeAccounting")}</option>
              </select>
            </label>
          )}
          {form.writeEnabled && form.purpose === "DECLARATIONS" && (
            <label style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 12.5, color: "var(--text-secondary)" }}>
              <input type="checkbox" checked={form.rootIsYearFolder} onChange={(e) => setForm({ ...form, rootIsYearFolder: e.target.checked })} />
              {t("ingest.rootIsYearFolder")}
            </label>
          )}
          <button className="primary" type="submit" disabled={create.isPending} style={{ justifySelf: "start" }}>
            {create.isPending ? "…" : t("ingest.connect")}
          </button>
        </form>
      </div>
      )}

      {/* The firm's outbound messaging providers live here too, so everything the firm configures is
          on one screen (this page is "Setări"). */}
      <EmailProviderSection />
      <WhatsAppProviderSection />
    </div>
  );
}

/**
 * Per-tenant nightly auto-sync schedule (V56): on/off + the local hour (Europe/Bucharest) it runs.
 * The backend tick fires hourly and only syncs tenants whose configured hour matches.
 */
function AutoSyncCard() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const settings = useQuery({ queryKey: ["settings"], queryFn: settingsApi.get });
  const [draft, setDraft] = useState<{ enabled: boolean; hour: number } | null>(null);
  const s = settings.data;
  const enabled = draft?.enabled ?? s?.autoSyncEnabled ?? true;
  const hour = draft?.hour ?? s?.autoSyncHour ?? 2;
  const dirty = s != null && (enabled !== s.autoSyncEnabled || hour !== s.autoSyncHour);

  const save = useMutation({
    mutationFn: () => settingsApi.updateAutoSync(enabled, hour),
    onSuccess: (updated) => { qc.setQueryData(["settings"], updated); setDraft(null); },
  });

  return (
    <div className="card">
      <h3 style={{ marginTop: 0, fontSize: 15 }}>{t("ingest.autoSyncTitle")}</h3>
      <p style={{ color: "var(--text-muted)", fontSize: 12.5, marginTop: 2 }}>{t("ingest.autoSyncHint")}</p>
      {settings.isLoading ? (
        <div style={{ color: "var(--text-muted)", fontSize: 13 }}>…</div>
      ) : (
        <div style={{ display: "flex", alignItems: "center", gap: 16, flexWrap: "wrap", marginTop: 8 }}>
          <label style={{ display: "inline-flex", alignItems: "center", gap: 8, fontSize: 13, color: "var(--text-secondary)", cursor: "pointer" }}>
            <input type="checkbox" checked={enabled} onChange={(e) => setDraft({ enabled: e.target.checked, hour })} />
            {t("ingest.autoSyncEnabled")}
          </label>
          <label style={{ display: "inline-flex", alignItems: "center", gap: 8, fontSize: 13, color: enabled ? "var(--text-secondary)" : "var(--text-muted)" }}>
            {t("ingest.autoSyncAt")}
            <select value={hour} disabled={!enabled} onChange={(e) => setDraft({ enabled, hour: Number(e.target.value) })} style={input}>
              {Array.from({ length: 24 }, (_, h) => (
                <option key={h} value={h}>{String(h).padStart(2, "0")}:00</option>
              ))}
            </select>
            <span style={{ fontSize: 11.5, color: "var(--text-muted)" }}>{t("ingest.autoSyncTz")}</span>
          </label>
          {dirty && (
            <button className="primary" disabled={save.isPending} onClick={() => save.mutate()}>
              {save.isPending ? "…" : t("common.save")}
            </button>
          )}
          {save.isError && <span style={{ color: "#b91c1c", fontSize: 12.5 }}>{save.error instanceof ApiError ? save.error.message : t("common.error")}</span>}
        </div>
      )}
    </div>
  );
}

const input: React.CSSProperties = { padding: "8px 10px", border: "1px solid var(--border)", borderRadius: 8, fontSize: 13, maxWidth: 520 };
