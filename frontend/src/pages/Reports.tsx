import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { documentsApi } from "../api/documents";
import { reportsApi, type ReportRow } from "../api/reports";
import { ApiError } from "../lib/apiClient";
import { usePeriod } from "../lib/period";
import { Icon } from "../components/Icon";
import { ReportChartsModal } from "../components/ReportChartsModal";
import { ReportEmailModal, type ReportTarget } from "../components/ReportEmailModal";
import { ReportLogModal } from "../components/ReportLogModal";

const dmy = (iso: string) => new Date(iso).toLocaleDateString("ro-RO", { day: "numeric", month: "short" });

/** MOD-06 Reports — monthly hub: upload trial balance, download branded report, charts, email to rep. */
export function Reports() {
  const { t } = useTranslation();
  const { period } = usePeriod();
  const qc = useQueryClient();
  const fileRef = useRef<HTMLInputElement>(null);
  const [uploadFor, setUploadFor] = useState<string | null>(null);
  const [uploadNote, setUploadNote] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [chartsFor, setChartsFor] = useState<{ id: string; name: string } | null>(null);
  const [logFor, setLogFor] = useState<{ id: string; name: string } | null>(null);
  const [sendList, setSendList] = useState<ReportTarget[] | null>(null);

  const companies = useQuery({ queryKey: ["companies"], queryFn: companiesApi.list });
  const reports = useQuery({ queryKey: ["reports", period], queryFn: () => reportsApi.list(period) });
  const rowBy = new Map<string, ReportRow>((reports.data ?? []).map((r) => [r.companyId, r]));

  const rows = companies.data ?? [];
  const hasReport = (id: string) => !!rowBy.get(id)?.uploadedAt;
  const selectableIds = rows.filter((c) => hasReport(c.id)).map((c) => c.id);
  const allSelected = selectableIds.length > 0 && selectableIds.every((id) => selected.has(id));

  useEffect(() => { setSelected(new Set()); }, [period]);

  const toggle = (id: string) => setSelected((p) => { const n = new Set(p); n.has(id) ? n.delete(id) : n.add(id); return n; });
  const toggleAll = () => setSelected(allSelected ? new Set() : new Set(selectableIds));
  const nameOf = (id: string) => rows.find((c) => c.id === id)?.legalName ?? id;
  const target = (id: string): ReportTarget => ({ companyId: id, companyName: nameOf(id) });

  const upload = useMutation({
    mutationFn: async ({ companyId, file }: { companyId: string; file: File }) =>
      documentsApi.upload(companyId, period, file, "TRIAL_BALANCE"),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ["reports", period] }); },
    onError: (e) => setUploadNote(t("reports.uploadFailed", { reason: e instanceof ApiError ? e.message : "—" })),
  });
  const pickUpload = (id: string) => { setUploadFor(id); fileRef.current?.click(); };
  const onFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0]; setUploadNote(null);
    if (f && uploadFor) upload.mutate({ companyId: uploadFor, file: f }); e.target.value = "";
  };

  const dot = (r?: ReportRow) => !r?.uploadedAt ? "var(--dot-red)" : r.balanced ? "var(--dot-green)" : "var(--dot-orange)";

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div>
        <div style={{ color: "var(--text-secondary)", fontSize: 12.5 }}>{t("reports.crumb")}</div>
        <h2 style={{ margin: "2px 0 0", fontSize: 21, letterSpacing: "-0.01em" }}>{t("reports.title")}</h2>
      </div>

      {selected.size > 0 && (
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", background: "var(--chrome-bg)", borderRadius: 10, padding: "9px 14px" }}>
          <span style={{ fontSize: 13.5, color: "var(--chrome-text)" }}><b style={{ color: "var(--primary)" }}>{selected.size}</b> {t("email.selected", { n: selected.size })}</span>
          <div style={{ display: "flex", gap: 8 }}>
            <button onClick={() => setSelected(new Set())} style={{ background: "var(--chrome-active)", color: "var(--chrome-text)", border: "1px solid #2a3a37" }}>{t("email.clear")}</button>
            <button className="primary" onClick={() => setSendList([...selected].map(target))}><Icon name="mail" size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />{t("email.sendN", { n: selected.size })}</button>
          </div>
        </div>
      )}

      <input ref={fileRef} type="file" accept="application/pdf" onChange={onFile} style={{ display: "none" }} />
      {uploadNote && (
        <div style={{ display: "flex", justifyContent: "space-between", gap: 8, background: "var(--warn-bg, #fef3c7)", border: "1px solid var(--warn-bd, #fcd34d)", color: "var(--warn-fg, #92400e)", borderRadius: 10, padding: "8px 12px", fontSize: 12.5 }}>
          <span>{uploadNote}</span><button onClick={() => setUploadNote(null)} style={{ background: "none", border: "none", cursor: "pointer", color: "inherit" }}><Icon name="x" size={14} /></button>
        </div>
      )}

      <div className="card" style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ minWidth: 940 }}>
          <div style={{ ...gridRow, background: "var(--th-bg)", ...thText }}>
            <div><input type="checkbox" checked={allSelected} disabled={selectableIds.length === 0} onChange={toggleAll} title={t("email.selectAll")} /></div>
            <div />
            <div>{t("documents.company")}</div>
            <div>{t("reports.trialBalance")}</div>
            <div>{t("reports.report")}</div>
            <div>{t("statements.lastSent")}</div>
            <div style={{ textAlign: "right" }}>{t("statements.actions")}</div>
          </div>

          {rows.map((c) => {
            const r = rowBy.get(c.id);
            const up = !!r?.uploadedAt;
            return (
              <div key={c.id} style={{ ...gridRow, borderTop: "1px solid var(--hair)", background: selected.has(c.id) ? "var(--row-active)" : undefined }}>
                <div>{up ? <input type="checkbox" checked={selected.has(c.id)} onChange={() => toggle(c.id)} /> : <span style={{ color: "var(--text-faint)" }}>·</span>}</div>
                <div><span style={{ display: "inline-block", width: 8, height: 8, borderRadius: "50%", background: dot(r) }} title={up ? (r!.balanced ? t("reports.balanced") : t("reports.unbalancedShort")) : t("reports.notUploaded")} /></div>
                <div>
                  <div style={{ fontWeight: 600 }}>{c.legalName}</div>
                  <div className="mono" style={{ color: "var(--text-muted)", fontSize: 11 }}>{c.cui}{c.locality ? ` · ${c.locality}` : ""}</div>
                </div>
                <div>
                  {up
                    ? <span className="pill round ok" title={t("reports.uploadedOn")}>{dmy(r!.uploadedAt!)}{r!.version > 1 ? ` · v${r!.version}` : ""}</span>
                    : <button className="pill round danger" style={{ cursor: "pointer", border: "1px solid var(--danger-bd, #fecaca)", font: "inherit" }} onClick={() => pickUpload(c.id)}>{t("reports.missing")}</button>}
                </div>
                <div style={{ display: "flex", gap: 6 }}>
                  <button style={{ ...iconBtn, opacity: up ? 1 : 0.4 }} title={t("reports.download")} disabled={!up} onClick={() => reportsApi.downloadPdf(c.id, period)}><Icon name="download" size={14} /></button>
                  <button style={{ ...iconBtn, opacity: up ? 1 : 0.4 }} title={t("reports.charts")} disabled={!up} onClick={() => setChartsFor({ id: c.id, name: c.legalName })}>📊</button>
                </div>
                <div>
                  {r?.lastSentAt
                    ? <button className="pill teal round" style={pillBtn} onClick={() => setLogFor({ id: c.id, name: c.legalName })}><Icon name="mail" size={11} style={{ verticalAlign: "-1px", marginRight: 4 }} />{dmy(r.lastSentAt)}{r.sentCount > 1 ? ` · ${r.sentCount}` : ""}</button>
                    : <button style={neverBtn} onClick={() => setLogFor({ id: c.id, name: c.legalName })} disabled={false}>{t("taxes.neverSent")} · <u>{t("taxes.sendShort")}</u></button>}
                </div>
                <div style={{ display: "flex", gap: 6, justifyContent: "flex-end" }}>
                  <button style={iconBtn} title={t("reports.upload")} disabled={upload.isPending} onClick={() => pickUpload(c.id)}><Icon name="upload" size={14} /></button>
                  <button style={{ ...iconBtn, opacity: up ? 1 : 0.4 }} title={t("reports.sendEmail")} disabled={!up} onClick={() => setSendList([target(c.id)])}><Icon name="mail" size={14} /></button>
                </div>
              </div>
            );
          })}
          {reports.data && rows.length === 0 && <div style={{ padding: 14, color: "var(--text-muted)" }}>{t("taxes.noCompanies")}</div>}
        </div>
      </div>

      {chartsFor && <ReportChartsModal companyId={chartsFor.id} companyName={chartsFor.name} period={period} onClose={() => setChartsFor(null)} />}
      {logFor && <ReportLogModal companyId={logFor.id} companyName={logFor.name} period={period} onClose={() => setLogFor(null)} onCompose={() => setSendList([target(logFor.id)])} />}
      {sendList && <ReportEmailModal targets={sendList} period={period} onClose={() => setSendList(null)} />}
    </div>
  );
}

const gridRow: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "30px 22px minmax(200px,1.4fr) 130px 96px 116px 92px",
  alignItems: "center", gap: 10, padding: "10px 16px",
};
const thText: React.CSSProperties = { fontSize: 9.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "#8a9794" };
const iconBtn: React.CSSProperties = { width: 28, height: 28, display: "grid", placeItems: "center", padding: 0, border: "1px solid var(--border)", borderRadius: 8, background: "var(--surface)", color: "#52605d", cursor: "pointer", fontSize: 13 };
const pillBtn: React.CSSProperties = { cursor: "pointer", border: "1px solid var(--teal-chip-bd)", font: "inherit" };
const neverBtn: React.CSSProperties = { background: "none", border: "1px dashed var(--border)", borderRadius: 999, padding: "1px 8px", fontSize: 11, color: "var(--primary-dark)", cursor: "pointer" };
