import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { declarationsApi, type DeclarationFile } from "../api/taxes";
import { documentsApi } from "../api/documents";
import { ingestionApi, type SyncResult } from "../api/ingestion";
import { ApiError } from "../lib/apiClient";
import { Icon } from "./Icon";

const money = (n: number) => n.toLocaleString("ro-RO", { minimumFractionDigits: 0 });
const dmy = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString("ro-RO", { day: "numeric", month: "short" }) : "—");

/** Manage the ANAF declarations for one company + month (B skin): list, structured preview, delete, upload. */
export function DeclarationsModal({ companyId, companyName, period, onClose }:
  { companyId: string; companyName: string; period: string; onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const fileRef = useRef<HTMLInputElement>(null);
  const [selId, setSelId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { data = [], isLoading } = useQuery({
    queryKey: ["declarations", companyId, period],
    queryFn: () => declarationsApi.list(companyId, period),
  });
  const selected: DeclarationFile | undefined = data.find((d) => d.id === selId) ?? data[0];

  const detail = useQuery({
    queryKey: ["declaration-detail", companyId, selected?.id],
    queryFn: () => declarationsApi.detail(companyId, selected!.id),
    enabled: !!selected,
  });

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ["declarations", companyId, period] });
    void qc.invalidateQueries({ queryKey: ["tax-list", period] });
    void qc.invalidateQueries({ queryKey: ["tax-payments", companyId, period] });
  };
  const upload = useMutation({
    mutationFn: (file: File) => documentsApi.upload(companyId, period, file),
    onSuccess: () => { setError(null); invalidate(); },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Upload failed"),
  });
  // Sync this company's declarations for the month from the Drive connection (if one covers this type).
  const driveQ = useQuery({ queryKey: ["ingestion-source", "DECLARATION"], queryFn: () => ingestionApi.source("DECLARATION") });
  const driveMode = driveQ.data?.driveEnabled === true;
  const sync = useMutation({
    mutationFn: () => ingestionApi.syncCompany({ companyId, period, type: "DECLARATION" }),
    onSuccess: (r: SyncResult) => { setError(null); invalidate(); window.alert(t("payroll.syncDone", r as unknown as Record<string, number>)); },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Sync failed"),
  });
  const remove = useMutation({
    mutationFn: (declarationId: string) => declarationsApi.remove(companyId, declarationId),
    onSuccess: () => { setSelId(null); invalidate(); },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Delete failed"),
  });
  const movePeriod = useMutation({
    mutationFn: ({ documentId, targetPeriod }: { documentId: string; targetPeriod: string }) =>
      documentsApi.movePeriod(companyId, documentId, targetPeriod),
    onSuccess: () => { setSelId(null); invalidate(); },
    onError: (e) => setError(e instanceof ApiError ? e.message : t("doc.movePeriod.error")),
  });

  const onPick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0]; if (f) upload.mutate(f); e.target.value = "";
  };
  const downloadPdf = async () => {
    if (!selected) return;
    const blob = await documentsApi.download(companyId, selected.documentId);
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url; a.download = `${selected.type}.pdf`; document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  return (
    <div style={overlay} onClick={onClose}>
      <div style={modal} onClick={(e) => e.stopPropagation()}>
        <div style={header}>
          <div>
            <div style={{ color: "var(--chrome-muted)", fontSize: 11 }}>{t("taxes.declarationsSub")}</div>
            <div style={{ color: "#f3f8f7", fontSize: 17, fontWeight: 700 }}>{t("taxes.declarations")} · {companyName}</div>
          </div>
          <button onClick={onClose} style={closeBtn}><Icon name="x" size={16} /></button>
        </div>

        {error && <p style={{ color: "var(--danger-fg)", padding: "8px 16px 0", margin: 0 }}>{error}</p>}

        <div style={{ display: "grid", gridTemplateColumns: "420px 1fr", gap: 0, flex: 1, minHeight: 0 }}>
          {/* file list */}
          <div style={{ borderRight: "1px solid var(--border)", padding: 12, overflowY: "auto", display: "flex", flexDirection: "column", gap: 8 }}>
            {driveMode && (
              <button style={{ width: "100%" }} disabled={sync.isPending} onClick={() => sync.mutate()}>
                <Icon name="reconcile" size={13} style={{ verticalAlign: "-2px", marginRight: 5 }} />
                {sync.isPending ? t("payroll.syncing") : t("payroll.syncFromDrive")}
              </button>
            )}
            <input ref={fileRef} type="file" accept="application/pdf" onChange={onPick} style={{ display: "none" }} />
            <button className="primary" style={{ width: "100%" }} disabled={upload.isPending} onClick={() => fileRef.current?.click()}>
              <Icon name="upload" size={13} style={{ verticalAlign: "-2px", marginRight: 5 }} />
              {upload.isPending ? t("taxes.uploading") : t("taxes.uploadDeclaration")}
            </button>
            {isLoading && <div style={{ color: "var(--text-muted)" }}>{t("common.loading")}</div>}
            {!isLoading && data.length === 0 && <div style={{ color: "var(--text-muted)", fontSize: 12.5 }}>{t("taxes.noDeclarations")}</div>}
            {data.map((d) => (
              <div key={d.id} onClick={() => setSelId(d.id)}
                style={{ border: `1px solid ${selected?.id === d.id ? "var(--teal-chip-bd)" : "var(--border)"}`,
                  background: selected?.id === d.id ? "var(--row-active)" : "var(--surface)",
                  borderRadius: 9, padding: "10px 11px", cursor: "pointer" }}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8 }}>
                  <b style={{ fontSize: 12.5 }}>{d.type}</b>
                  <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    <span className="mono" style={{ fontSize: 12.5, fontWeight: 600 }}>{money(d.computedTotal)}</span>
                    <button title={t("common.delete")} onClick={(e) => { e.stopPropagation(); remove.mutate(d.id); }}
                      disabled={remove.isPending} style={{ background: "none", border: "none", color: "var(--danger-fg)", cursor: "pointer", padding: 0 }}>
                      <Icon name="trash" size={14} />
                    </button>
                  </div>
                </div>
                <div className="mono" style={{ fontSize: 11, color: "var(--text-muted)", marginTop: 2 }}>CUI: {d.cui ?? "—"}</div>
                <div style={{ display: "flex", flexWrap: "wrap", gap: 4, marginTop: 6 }}>
                  {d.sentCount > 0
                    ? <span className="pill ok">✓ {t("taxes.sentTimes", { n: d.sentCount })} · {dmy(d.lastSentAt)}</span>
                    : <span className="pill muted">{t("taxes.notSent")}</span>}
                  {d.mismatch && <span className="pill warn" title={t("taxes.mismatchTip", { declared: d.declaredTotal })}>MISMATCH</span>}
                  {d.outsidePeriod && (
                    <>
                      <span className="pill info" title={t("taxes.outsidePeriod")}>{t("docs.outsidePeriod")}</span>
                      {d.declPeriod && (
                        <button
                          onClick={(e) => { e.stopPropagation(); movePeriod.mutate({ documentId: d.documentId, targetPeriod: d.declPeriod! }); }}
                          disabled={movePeriod.isPending}
                          title={t("doc.movePeriod.tip", { period: d.declPeriod?.slice(0, 7) })}
                          style={{ fontSize: 10.5, padding: "2px 9px", borderRadius: 999, cursor: "pointer",
                            border: "1px solid #d97706", background: "#fffbeb", color: "#92400e", fontWeight: 600 }}>
                          {t("doc.movePeriod.btn", { period: d.declPeriod?.slice(0, 7) })}
                        </button>
                      )}
                    </>
                  )}
                  {d.wrongParty && <span className="pill danger" title={t("taxes.wrongPartyTip")}>{t("taxes.wrongParty")}</span>}
                  {d.duplicate && <span className="pill muted" title={t("taxes.duplicateTip")}>{t("taxes.duplicate")}</span>}
                </div>
              </div>
            ))}
          </div>

          {/* structured preview */}
          <div style={{ background: "var(--th-bg)", padding: 16, overflowY: "auto" }}>
            {!selected && <div style={{ color: "var(--text-muted)" }}>{t("files.preview")}</div>}
            {selected && (
              <>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 10 }}>
                  <b>{t("taxes.declarationPreview", { type: selected.type })}</b>
                  <button onClick={downloadPdf}><Icon name="download" size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />{t("taxes.downloadPdf")}</button>
                </div>
                {detail.isLoading && <p>{t("common.loading")}</p>}
                {detail.data && (
                  <div style={{ background: "var(--surface)", border: "1px solid var(--border)", borderRadius: 10, padding: 14 }}>
                    <dl style={{ display: "grid", gridTemplateColumns: "140px 1fr", rowGap: 5, margin: 0, fontSize: 13 }}>
                      <dt style={dt}>{t("taxes.declarationPeriod")}</dt><dd style={dd} className="mono">{dmy(detail.data.period)}</dd>
                      <dt style={dt}>CUI</dt><dd style={dd} className="mono">{detail.data.cui ?? "—"}</dd>
                      <dt style={dt}>{t("taxes.total")}</dt>
                      <dd style={dd}><b className="mono">{money(detail.data.computedTotal)}</b>
                        {detail.data.mismatch && <span style={{ color: "#b45309", marginLeft: 6 }}>⚠ {t("taxes.mismatchTip", { declared: detail.data.declaredTotal })}</span>}
                      </dd>
                    </dl>
                    <div style={{ marginTop: 12 }}>
                      <div style={{ ...obRow, ...thRow }}>
                        <div>{t("taxes.category")}</div><div>Cod</div><div>{t("taxes.due")}</div>
                        <div style={{ textAlign: "right" }}>{t("taxes.amount")}</div>
                      </div>
                      {detail.data.obligations.map((o, i) => (
                        <div key={i} style={obRow}>
                          <div>{o.category}{o.refund ? ` (${t("taxes.refund")})` : ""}</div>
                          <div className="mono">{o.codOblig}</div>
                          <div className="mono">{dmy(o.scadenta)}</div>
                          <div className="mono" style={{ textAlign: "right" }}>{money(o.amount)}</div>
                        </div>
                      ))}
                    </div>
                    <p style={{ color: "var(--text-muted)", fontSize: 11, marginTop: 10 }}>{t("taxes.xfaNote")}</p>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex",
  alignItems: "flex-start", justifyContent: "center", padding: "4vh 16px", zIndex: 50,
};
const modal: React.CSSProperties = {
  background: "var(--surface)", borderRadius: 14, width: "min(1080px, 97vw)", height: "84vh",
  display: "flex", flexDirection: "column", overflow: "hidden", boxShadow: "var(--shadow-modal)",
};
const header: React.CSSProperties = {
  display: "flex", justifyContent: "space-between", alignItems: "center", background: "var(--chrome-bg)", padding: "12px 16px",
};
const closeBtn: React.CSSProperties = { background: "none", border: "none", color: "var(--chrome-text)", cursor: "pointer" };
const dt: React.CSSProperties = { color: "var(--text-muted)" };
const dd: React.CSSProperties = { margin: 0 };
const obRow: React.CSSProperties = { display: "grid", gridTemplateColumns: "1.4fr 70px 70px 90px", gap: 8, padding: "6px 4px", borderTop: "1px solid var(--hair)", fontSize: 12.5, alignItems: "center" };
const thRow: React.CSSProperties = { borderTop: "none", background: "var(--th-bg-sub)", fontSize: 9.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--text-muted)" };
