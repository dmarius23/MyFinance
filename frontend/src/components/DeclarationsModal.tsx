import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { declarationsApi, type DeclarationFile } from "../api/taxes";
import { documentsApi } from "../api/documents";
import { ApiError } from "../lib/apiClient";

const money = (n: number) => n.toLocaleString("ro-RO");
const dmy = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString("ro-RO") : "—");

/** Manage the ANAF declarations uploaded for one company + month: list, structured preview, delete, upload. */
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
  const remove = useMutation({
    mutationFn: (declarationId: string) => declarationsApi.remove(companyId, declarationId),
    onSuccess: () => { setSelId(null); invalidate(); },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Delete failed"),
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
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h2 style={{ margin: 0 }}>{t("taxes.declarations")} — {companyName}</h2>
          <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <input ref={fileRef} type="file" accept="application/pdf" onChange={onPick} style={{ display: "none" }} />
            <button className="primary" onClick={() => fileRef.current?.click()} disabled={upload.isPending}>
              {upload.isPending ? t("taxes.uploading") : "+ " + t("taxes.uploadDeclaration")}
            </button>
            <button onClick={onClose} style={{ border: "none", background: "none", fontSize: 18, cursor: "pointer" }}>✕</button>
          </div>
        </div>

        {error && <p style={{ color: "#dc2626" }}>{error}</p>}
        {isLoading && <p>{t("common.loading")}</p>}

        <div style={{ display: "grid", gridTemplateColumns: "340px 1fr", gap: 14, marginTop: 12, alignItems: "start" }}>
          <div style={{ maxHeight: 560, overflow: "auto" }}>
            {!isLoading && data.length === 0 && <div style={{ color: "var(--text-muted)" }}>{t("taxes.noDeclarations")}</div>}
            {data.map((d) => (
              <div key={d.id} onClick={() => setSelId(d.id)}
                style={{
                  display: "flex", alignItems: "center", gap: 8, padding: "8px 10px", borderRadius: 8,
                  cursor: "pointer", marginBottom: 6,
                  background: selected?.id === d.id ? "var(--primary-light, #eef2ff)" : "var(--card-bg,#fff)",
                  border: "1px solid var(--border)",
                }}>
                {d.outsidePeriod && <span title={t("taxes.outsidePeriod")} style={{ fontSize: 14 }}>📅</span>}
                <div style={{ flex: 1 }}>
                  <div><b>{d.type}</b> · {money(d.computedTotal)}
                    {d.mismatch && <span title={t("taxes.mismatch")} style={{ color: "#b45309", marginLeft: 4 }}>⚠</span>}
                  </div>
                  <div style={{ fontSize: 11, color: "var(--text-muted)" }}>CUI: {d.cui ?? "—"}</div>
                  {d.wrongParty && <span title={t("taxes.wrongPartyTip")} style={chip("#fee2e2", "#991b1b", "#fecaca")}>{t("taxes.wrongParty")}</span>}
                  {d.duplicate && <span title={t("taxes.duplicateTip")} style={chip("#f3f4f6", "#6b7280", "#e5e7eb")}>{t("taxes.duplicate")}</span>}
                </div>
                <button title={t("common.delete")}
                  onClick={(e) => { e.stopPropagation(); remove.mutate(d.id); }}
                  disabled={remove.isPending}
                  style={{ color: "#dc2626", border: "none", background: "none", cursor: "pointer", fontSize: 14 }}>✕</button>
              </div>
            ))}
          </div>

          {/* Structured preview (ANAF PDFs are XFA dynamic forms — they don't render in browser viewers). */}
          <div style={{ minHeight: 420, border: "1px solid var(--border)", borderRadius: 8, padding: 14 }}>
            {!selected && <div style={{ color: "var(--text-muted)" }}>{t("files.preview")}</div>}
            {selected && (
              <>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                  <h3 style={{ margin: 0 }}>{t("taxes.declarationPreview", { type: selected.type })}</h3>
                  <button onClick={downloadPdf}>⤓ {t("taxes.downloadPdf")}</button>
                </div>
                {detail.isLoading && <p>{t("common.loading")}</p>}
                {detail.data && (
                  <div style={{ marginTop: 10 }}>
                    <dl style={{ display: "grid", gridTemplateColumns: "140px 1fr", rowGap: 4, margin: 0, fontSize: 13 }}>
                      <dt style={dt}>{t("taxes.declarationPeriod")}</dt><dd style={dd}>{dmy(detail.data.period)}</dd>
                      <dt style={dt}>CUI</dt><dd style={dd}>{detail.data.cui ?? "—"}</dd>
                      <dt style={dt}>{t("taxes.total")}</dt><dd style={dd}><b>{money(detail.data.computedTotal)}</b>
                        {detail.data.mismatch && <span style={{ color: "#b45309", marginLeft: 6 }}>
                          ⚠ {t("taxes.mismatchTip", { declared: detail.data.declaredTotal })}</span>}
                      </dd>
                    </dl>
                    <table style={{ width: "100%", borderCollapse: "collapse", marginTop: 12 }}>
                      <thead><tr style={{ textAlign: "left", color: "var(--text-muted)", fontSize: 12 }}>
                        <td style={cell}>{t("taxes.category")}</td><td style={cell}>Cod</td>
                        <td style={cell}>{t("taxes.due")}</td><td style={{ ...cell, textAlign: "right" }}>{t("taxes.amount")}</td>
                      </tr></thead>
                      <tbody>
                        {detail.data.obligations.map((o, i) => (
                          <tr key={i} style={{ borderTop: "1px solid var(--border)" }}>
                            <td style={cell}>{o.category}{o.refund ? ` (${t("taxes.refund")})` : ""}</td>
                            <td style={cell}>{o.codOblig}</td>
                            <td style={cell}>{dmy(o.scadenta)}</td>
                            <td style={{ ...cell, textAlign: "right" }}>{money(o.amount)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
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

function chip(bg: string, fg: string, bd: string): React.CSSProperties {
  return { background: bg, color: fg, border: `1px solid ${bd}`, borderRadius: 999, padding: "0 8px",
    fontSize: 11, display: "inline-block", marginTop: 3, marginRight: 4 };
}

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex",
  alignItems: "flex-start", justifyContent: "center", padding: "5vh 16px", zIndex: 50, overflowY: "auto",
};
const modal: React.CSSProperties = { background: "var(--card-bg, #fff)", borderRadius: 12, padding: 20, width: "min(980px, 100%)" };
const dt: React.CSSProperties = { color: "var(--text-muted)" };
const dd: React.CSSProperties = { margin: 0 };
const cell: React.CSSProperties = { padding: 5 };
