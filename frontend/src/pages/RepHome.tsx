import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { portalApi } from "../api/portal";
import { useAuth } from "../auth/AuthProvider";
import { ApiError } from "../lib/apiClient";

const money = (n: number) => (n ?? 0).toLocaleString("ro-RO", { minimumFractionDigits: 0, maximumFractionDigits: 0 });
const monthLabel = (period: string, lang: string) =>
  new Date(period).toLocaleDateString(lang === "ro" ? "ro-RO" : "en-US", { month: "long", year: "numeric" });
const shiftMonth = (period: string, delta: number) => {
  const d = new Date(period); d.setMonth(d.getMonth() + delta);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-01`;
};
const thisMonth = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-01`; };

/** Representative PWA — mobile-first home: company + month, upload, missing-docs checklist, my uploads,
 *  read-only report and payroll. Scoped to the rep's single company. */
export function RepHome() {
  const { t, i18n } = useTranslation();
  const { signOut } = useAuth();
  const qc = useQueryClient();
  const [period, setPeriod] = useState(thisMonth());
  const cameraRef = useRef<HTMLInputElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const me = useQuery({ queryKey: ["portal-me"], queryFn: portalApi.me });
  const notifs = useQuery({ queryKey: ["portal-notifs"], queryFn: portalApi.notifications, refetchInterval: 30000, refetchOnWindowFocus: true });
  const markRead = useMutation({
    mutationFn: (id: string) => portalApi.markNotificationRead(id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["portal-notifs"] }),
  });
  const missing = useQuery({ queryKey: ["portal-missing", period], queryFn: () => portalApi.missing(period) });
  const myDocs = useQuery({ queryKey: ["portal-docs", period], queryFn: () => portalApi.myDocuments(period) });
  const report = useQuery({ queryKey: ["portal-report", period], queryFn: () => portalApi.report(period) });
  const payroll = useQuery({ queryKey: ["portal-payroll", period], queryFn: () => portalApi.payroll(period) });

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["portal-docs", period] });
    void qc.invalidateQueries({ queryKey: ["portal-missing", period] });
  };
  const upload = useMutation({
    mutationFn: (files: File[]) => Promise.all(files.map((f) => portalApi.uploadDocument(f, period))),
    onSuccess: refresh,
  });
  const onPick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []); e.target.value = "";
    if (files.length) upload.mutate(files);
  };

  const r = report.data;

  return (
    <div style={{ maxWidth: 480, margin: "0 auto", padding: 16, display: "grid", gap: 14 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <div>
          <h1 style={{ color: "var(--primary)", margin: 0, fontSize: 22 }}>MyFinance</h1>
          <div style={{ fontSize: 13, color: "var(--text-secondary)", fontWeight: 600 }}>{me.data?.name ?? "…"}</div>
        </div>
        <button onClick={() => void signOut()}>{t("auth.logout")}</button>
      </div>

      {/* Month selector */}
      <div style={{ display: "flex", alignItems: "center", justifyContent: "center", gap: 14, background: "var(--th-bg)", borderRadius: 10, padding: "8px 12px" }}>
        <button onClick={() => setPeriod((p) => shiftMonth(p, -1))} aria-label="prev">‹</button>
        <span style={{ fontWeight: 600, fontSize: 14, textTransform: "capitalize" }}>{monthLabel(period, i18n.language)}</span>
        <button onClick={() => setPeriod((p) => shiftMonth(p, 1))} aria-label="next">›</button>
      </div>

      {/* Notifications */}
      {(notifs.data ?? []).length > 0 && (
        <div className="card">
          <h2 style={{ marginTop: 0, fontSize: 16 }}>{t("notif.title")}</h2>
          {(notifs.data ?? []).slice(0, 6).map((n, i) => (
            <button key={n.id} onClick={() => { if (!n.readAt) markRead.mutate(n.id); }}
              style={{ display: "block", width: "100%", textAlign: "left", border: "none", borderTop: i ? "1px solid var(--hair)" : "none", padding: "9px 0", background: "none", cursor: n.readAt ? "default" : "pointer", font: "inherit" }}>
              <div style={{ display: "flex", gap: 7, alignItems: "center" }}>
                {!n.readAt && <span style={{ width: 7, height: 7, borderRadius: "50%", background: "var(--primary)", flexShrink: 0 }} />}
                <span style={{ fontSize: 13.5, fontWeight: 600 }}>{n.title}</span>
              </div>
              <div style={{ fontSize: 12.5, color: "var(--text-secondary)" }}>{n.body}</div>
            </button>
          ))}
        </div>
      )}

      {/* Upload */}
      <div className="card">
        <h2 style={{ marginTop: 0, fontSize: 16 }}>{t("portal.uploadTitle")}</h2>
        <p style={{ color: "var(--text-muted)", fontSize: 13, marginTop: 2 }}>{t("portal.uploadHint")}</p>
        <input ref={cameraRef} type="file" accept="image/*" capture="environment" onChange={onPick} style={{ display: "none" }} />
        <input ref={fileRef} type="file" accept="application/pdf,image/*" multiple onChange={onPick} style={{ display: "none" }} />
        <div style={{ display: "grid", gap: 8, marginTop: 8 }}>
          <button className="primary" style={{ padding: 12, fontSize: 15 }} disabled={upload.isPending} onClick={() => cameraRef.current?.click()}>📷 {t("portal.takePhoto")}</button>
          <button style={{ padding: 12, fontSize: 15 }} disabled={upload.isPending} onClick={() => fileRef.current?.click()}>📎 {t("portal.chooseFile")}</button>
        </div>
        {upload.isPending && <p style={{ color: "var(--text-secondary)", fontSize: 13, marginTop: 8 }}>{t("portal.uploading")}</p>}
        {upload.isError && <p style={{ color: "var(--danger-fg, #b91c1c)", fontSize: 13, marginTop: 8 }}>{upload.error instanceof ApiError ? upload.error.message : t("portal.failed")}</p>}
      </div>

      {/* Missing documents */}
      <div className="card">
        <h2 style={{ marginTop: 0, fontSize: 16 }}>{t("portal.missingTitle")}</h2>
        {missing.isLoading && <p style={{ color: "var(--text-muted)", fontSize: 13 }}>…</p>}
        {!missing.isLoading && (missing.data ?? []).length === 0 && (
          <p style={{ color: "#16a34a", fontSize: 13.5, margin: "4px 0 0" }}>✓ {t("portal.allGood")}</p>
        )}
        {(missing.data ?? []).map((m, i) => (
          <div key={i} style={{ display: "flex", justifyContent: "space-between", gap: 8, padding: "8px 0", borderTop: i ? "1px solid var(--hair)" : "none" }}>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 13, fontWeight: 600, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{m.partnerName ?? m.description ?? "—"}</div>
              <div className="mono" style={{ fontSize: 11.5, color: "var(--text-muted)" }}>{m.txnDate}</div>
            </div>
            <span className="mono" style={{ fontSize: 13, fontWeight: 600, whiteSpace: "nowrap" }}>{money(m.amount)} RON</span>
          </div>
        ))}
      </div>

      {/* My uploads */}
      <div className="card">
        <h2 style={{ marginTop: 0, fontSize: 16 }}>{t("portal.myUploads")}</h2>
        {(myDocs.data ?? []).length === 0 && <p style={{ color: "var(--text-faint)", fontSize: 13 }}>—</p>}
        {(myDocs.data ?? []).map((d) => (
          <button key={d.id} onClick={() => portalApi.downloadFile(d.id, d.filename)}
            style={{ display: "flex", width: "100%", justifyContent: "space-between", alignItems: "center", gap: 8, padding: "8px 0", borderTop: "1px solid var(--hair)", background: "none", border: "none", cursor: "pointer", font: "inherit", textAlign: "left" }}>
            <span style={{ fontSize: 13, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{d.filename}</span>
            <span style={{ fontSize: 16, color: "var(--primary)" }}>⤓</span>
          </button>
        ))}
      </div>

      {/* Report */}
      <div className="card">
        <h2 style={{ marginTop: 0, fontSize: 16 }}>{t("portal.report")}</h2>
        {report.isLoading && <p style={{ color: "var(--text-muted)", fontSize: 13 }}>…</p>}
        {!report.isLoading && !r && <p style={{ color: "var(--text-faint)", fontSize: 13 }}>{t("portal.reportNotReady")}</p>}
        {r && (
          <>
            <div style={{ display: "flex", gap: 14, margin: "6px 0 10px" }}>
              <Kpi label={t("reports.chart.pl")} value={`${money(r.profitLoss.revenue)}`} sub="Venituri" />
              <Kpi label="" value={`${money(r.profitLoss.netProfit)}`} sub="Profit net" />
            </div>
            <button className="primary" style={{ width: "100%", padding: 11 }} onClick={() => portalApi.downloadReport(period)}>⤓ {t("portal.downloadReport")}</button>
          </>
        )}
      </div>

      {/* Payroll */}
      {(payroll.data ?? []).length > 0 && (
        <div className="card">
          <h2 style={{ marginTop: 0, fontSize: 16 }}>{t("portal.payroll")}</h2>
          {(payroll.data ?? []).map((p) => (
            <button key={p.id} onClick={() => portalApi.downloadFile(p.id, p.filename)}
              style={{ display: "flex", width: "100%", justifyContent: "space-between", alignItems: "center", gap: 8, padding: "8px 0", borderTop: "1px solid var(--hair)", background: "none", border: "none", cursor: "pointer", font: "inherit", textAlign: "left" }}>
              <span style={{ fontSize: 13, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{p.filename}</span>
              <span style={{ fontSize: 16, color: "var(--primary)" }}>⤓</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function Kpi({ label, value, sub }: { label: string; value: string; sub: string }) {
  return (
    <div style={{ flex: 1 }}>
      <div className="mono" style={{ fontSize: 19, fontWeight: 700 }}>{value}</div>
      <div style={{ fontSize: 11, color: "var(--text-muted)" }}>{sub}{label ? "" : ""}</div>
    </div>
  );
}
