import { Fragment, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { documentsApi, DOCUMENT_TYPES, type Document } from "../api/documents";
import { reconciliationApi } from "../api/bank";
import { InvoicePaymentsModal } from "./InvoicePaymentsModal";

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)",
  display: "grid", placeItems: "center", zIndex: 50, padding: "3vh 12px",
};
const modalBox: React.CSSProperties = {
  background: "var(--surface)", borderRadius: 14, width: 1340, maxWidth: "97vw", maxHeight: "92vh",
  display: "flex", flexDirection: "column", overflow: "hidden", boxShadow: "var(--shadow-modal)",
};
const darkHeader: React.CSSProperties = {
  display: "flex", justifyContent: "space-between", alignItems: "center", background: "var(--chrome-bg)", padding: "12px 16px",
};

export function FilesModal({ companyId, companyName, period, onClose }:
  { companyId: string; companyName: string; period: string; onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data = [] } = useQuery({
    queryKey: ["documents", companyId, period],
    queryFn: () => documentsApi.list(companyId, period),
  });
  const { data: statuses = [] } = useQuery({
    queryKey: ["doc-status", companyId, period],
    queryFn: () => reconciliationApi.documentStatus(companyId, period),
  });
  const statusByDoc = new Map(statuses.map((s) => [s.documentId, s]));
  // Only intake documents belong on this screen. Declarations live on Taxes & payments, trial balances
  // on Reports, and payroll on the Payroll screen — exclude them so they aren't lumped with invoices.
  const docs = data.filter((d) => !["DECLARATION", "TRIAL_BALANCE", "PAYROLL"].includes(d.type));
  // Bank statements first, then a divider, then invoices/receipts (stable within each group).
  const ordered = [...docs].sort((a, b) =>
    (a.type === "BANK_STATEMENT" ? 0 : 1) - (b.type === "BANK_STATEMENT" ? 0 : 1));
  const [selId, setSelId] = useState<string | null>(null);
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [uploadCount, setUploadCount] = useState(0);
  const [paymentsFor, setPaymentsFor] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const selected: Document | undefined = docs.find((d) => d.id === selId) ?? docs[0];

  useEffect(() => {
    let revoked: string | null = null;
    if (selected) {
      void documentsApi.download(companyId, selected.id).then((blob) => {
        const url = URL.createObjectURL(blob);
        revoked = url;
        setBlobUrl(url);
      });
    } else {
      setBlobUrl(null);
    }
    return () => { if (revoked) URL.revokeObjectURL(revoked); };
  }, [companyId, selected?.id]);

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ["documents", companyId, period] });
    void qc.invalidateQueries({ queryKey: ["doc-summary", period] });
    void qc.invalidateQueries({ queryKey: ["doc-status", companyId, period] });
    // type changes affect extraction/matching too
    void qc.invalidateQueries({ queryKey: ["bank-txns", companyId, period] });
    void qc.invalidateQueries({ queryKey: ["recon-summary", period] });
    void qc.invalidateQueries({ queryKey: ["invoices", companyId, period] });
  };
  const upload = useMutation({
    // Upload sequentially: each upload triggers synchronous extraction + reconciliation for the
    // period, so serializing avoids races on the shared matching state. Invalidate once at the end.
    mutationFn: async (files: File[]) => {
      for (const f of files) {
        await documentsApi.upload(companyId, period, f);
      }
    },
    onSuccess: invalidate,
  });
  const remove = useMutation({
    mutationFn: (id: string) => documentsApi.remove(companyId, id),
    onSuccess: invalidate,
  });
  const changeType = useMutation({
    mutationFn: ({ id, type }: { id: string; type: string }) => documentsApi.changeType(companyId, id, type),
    onSuccess: invalidate,
  });
  const reclassify = useMutation({
    mutationFn: () => documentsApi.reclassify(companyId, period),
    onSuccess: invalidate,
  });

  return (
    <>
    <div style={overlay} onClick={onClose}>
      <div style={modalBox} onClick={(e) => e.stopPropagation()}>
        <div style={darkHeader}>
          <div>
            <div style={{ color: "var(--chrome-muted)", fontSize: 11 }}>{t("files.title")}</div>
            <div style={{ color: "#f3f8f7", fontSize: 17, fontWeight: 700 }}>{companyName}</div>
          </div>
          <div style={{ display: "flex", gap: 8 }}>
            <button onClick={() => reclassify.mutate()} disabled={reclassify.isPending} title={t("files.rescanHint")}
              style={{ background: "var(--chrome-active)", color: "var(--chrome-text)", border: "1px solid #2a3a37" }}>
              {reclassify.isPending ? "…" : `↻ ${t("files.rescan")}`}
            </button>
            <button onClick={onClose} style={{ background: "none", border: "none", color: "var(--chrome-text)", cursor: "pointer", fontSize: 16 }}>✕</button>
          </div>
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "460px 1fr", gap: 14, padding: 16, overflowY: "auto", alignItems: "start" }}>
          <div>
            <div style={{ maxHeight: 560, overflow: "auto" }}>
              {ordered.length === 0 && <div style={{ color: "var(--text-muted)" }}>{t("files.none")}</div>}
              {ordered.map((d, i) => {
                const st = statusByDoc.get(d.id);
                const isStmt = d.type === "BANK_STATEMENT";
                const showHeader = i === 0 || (ordered[i - 1].type === "BANK_STATEMENT") !== isStmt;
                const isInvoice = d.type === "INVOICE" || d.type === "RECEIPT";
                const wrongType = !["BANK_STATEMENT", "INVOICE", "RECEIPT"].includes(d.type);
                const pay = st?.paymentStatus; // UNPAID | PARTIAL | PAID | null
                const payStyle = pay === "PAID" ? { bg: "#dcfce7", bd: "#16a34a" }
                  : pay === "PARTIAL" ? { bg: "#fef3c7", bd: "#d97706" }
                  : { bg: "#fee2e2", bd: "#dc2626" }; // UNPAID / no association
                const dateStyle = st?.dateFlag === "ORANGE" ? { bg: "#fef3c7", bd: "#d97706" }
                  : { bg: "#fee2e2", bd: "#dc2626" }; // RED
                const chip = (bg: string, color: string, bd: string): React.CSSProperties => ({
                  background: bg, color, border: `1px solid ${bd}`, borderRadius: 999,
                  padding: "1px 7px", fontSize: 10, fontWeight: 600, textTransform: "uppercase",
                });
                const typeSelect = (
                  <select
                    value={d.type}
                    disabled={changeType.isPending}
                    onClick={(e) => e.stopPropagation()}
                    onChange={(e) => changeType.mutate({ id: d.id, type: e.target.value })}
                    style={{
                      fontSize: 10.5, padding: "1px 4px", borderRadius: 6,
                      border: "1px solid var(--border)",
                      background: d.type === "UNCLASSIFIED" ? "#fef3c7" : "#eef2ff",
                      color: d.type === "UNCLASSIFIED" ? "#92400e" : "#3730a3",
                    }}
                  >
                    {DOCUMENT_TYPES.map((dt) => (
                      <option key={dt} value={dt}>{t(`documentType.${dt}`, { defaultValue: dt })}</option>
                    ))}
                  </select>
                );
                // Advisory labels shown on the last line (duplicate / wrong party / outside period).
                const labels = (
                  <>
                    {wrongType && (
                      <span title={t("doc.warn.wrongType")} style={chip("#fee2e2", "#b91c1c", "#fecaca")}>
                        {t("doc.wrongTypeChip")}
                      </span>
                    )}
                    {st?.duplicate && (
                      <span title={t("doc.warn.duplicate")} style={chip("#fee2e2", "#b91c1c", "#fecaca")}>
                        {t("doc.duplicateChip")}
                      </span>
                    )}
                    {isInvoice && st?.wrongParty === true && (
                      <span title={t("doc.warn.wrongParty")} style={chip("#fee2e2", "#991b1b", "#fecaca")}>
                        {t("doc.wrongPartyChip")}
                      </span>
                    )}
                    {isInvoice && (st?.wrongParty === null || st?.wrongParty === undefined) && (
                      <span title={t("doc.warn.unidentifiedParty")} style={chip("#f3f4f6", "#6b7280", "#e5e7eb")}>
                        {t("doc.unidentifiedChip")}
                      </span>
                    )}
                    {isInvoice && st?.dateFlag && (
                      <span title={t("docs.outsidePeriodTip")} style={chip("#fef3c7", "#92400e", "#fcd34d")}>
                        {t("docs.outsidePeriod")}
                      </span>
                    )}
                  </>
                );
                return (
                <Fragment key={d.id}>
                {showHeader && (
                  <div style={{ margin: i === 0 ? "0 2px 6px" : "10px 2px 6px",
                    paddingTop: i === 0 ? 0 : 6, borderTop: i === 0 ? "none" : "1px solid var(--border)",
                    fontSize: 11, fontWeight: 700, color: "var(--text-muted)", textTransform: "uppercase" }}>
                    {isStmt ? t("files.bankStatements") : t("statements.invoices")}
                  </div>
                )}
                <div
                  onClick={() => setSelId(d.id)}
                  style={{
                    display: "flex", alignItems: "center", gap: 8, padding: "8px 10px",
                    borderRadius: 9, cursor: "pointer", marginBottom: 4,
                    border: `1px solid ${selected?.id === d.id ? "var(--primary)" : "transparent"}`,
                    background: selected?.id === d.id ? "var(--primary-light, #eef2ff)" : "transparent",
                  }}
                >
                  <div style={{ flex: 1, minWidth: 0 }}>
                    {isInvoice ? (
                      <>
                        {/* 1) supplier + CUI (bold, ellipsis when long) — amount · date always visible on the right */}
                        <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
                          <span style={{ flex: 1, minWidth: 0, fontWeight: 700, fontSize: 12.5, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis",
                            color: st?.wrongParty === true ? "#b91c1c" : "var(--text)" }}>
                            {st?.issuer ?? "—"}{st?.issuerCif ? ` (CUI ${st.issuerCif})` : ""}
                          </span>
                          <span style={{ flex: "none", fontWeight: 700, fontSize: 12.5, whiteSpace: "nowrap",
                            color: st?.wrongParty === true ? "#b91c1c" : "var(--text)" }}>
                            {st?.total != null ? st.total.toFixed(2) : "—"}{"  ·  "}{st?.invoiceDate ?? "—"}
                          </span>
                        </div>
                        {/* 2) receiver (current company) CIF */}
                        <div style={{ fontSize: 11.5, marginTop: 2, color: "var(--text-muted)" }}>
                          {t("doc.cifClient")}: {st?.clientCif ?? "—"}
                        </div>
                        {/* 3) file name (regular) */}
                        <div style={{ fontSize: 11, marginTop: 2, color: "var(--text-muted)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                          {d.originalFilename}
                        </div>
                        {/* 4) type dropdown + advisory labels */}
                        <div style={{ display: "flex", alignItems: "center", gap: 6, marginTop: 4, flexWrap: "wrap" }}>
                          {typeSelect}
                          {labels}
                        </div>
                      </>
                    ) : (
                      <>
                        <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                          {st?.dateFlag && (
                            <span title={t(`doc.warn.${st.dateReason}`, { defaultValue: "" })}
                              style={{ background: dateStyle.bg, border: `1px solid ${dateStyle.bd}`, color: dateStyle.bd,
                                borderRadius: 6, fontSize: 11, lineHeight: 1, padding: "2px 5px", flexShrink: 0 }}>📅</span>
                          )}
                          <span style={{ fontWeight: 600, fontSize: 12.5, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                            {d.originalFilename}
                          </span>
                        </div>
                        <div style={{ display: "flex", alignItems: "center", gap: 6, marginTop: 3, flexWrap: "wrap" }}>
                          {typeSelect}
                          {labels}
                        </div>
                      </>
                    )}
                  </div>
                  {/* Aligned, compact action icons. */}
                  <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                    {isInvoice && (
                      // Money icon doubles as payment status + opens the payments view. Red = unpaid /
                      // no transaction associated, orange = partial, green = paid.
                      <button onClick={(e) => { e.stopPropagation(); setPaymentsFor(d.id); }}
                        title={`${t("recon.payments")}${pay ? " · " + t(`recon.status.${pay}`) : ""}`}
                        style={{ background: payStyle.bg, border: `1px solid ${payStyle.bd}`, color: payStyle.bd,
                          borderRadius: 7, cursor: "pointer", fontSize: 13, lineHeight: 1, padding: "3px 6px", width: 30, textAlign: "center" }}>
                        💰
                      </button>
                    )}
                    <button onClick={(e) => {
                        e.stopPropagation();
                        if (window.confirm(t("files.confirmDelete", { name: d.originalFilename })))
                          remove.mutate(d.id);
                      }} title={t("files.delete")}
                      style={{ border: "1px solid var(--border)", background: "#fff", color: "#dc2626",
                        borderRadius: 7, cursor: "pointer", fontSize: 12, lineHeight: 1, padding: "4px 7px" }}>✕</button>
                  </div>
                </div>
                </Fragment>
                );
              })}
            </div>
            <input
              ref={fileRef}
              type="file"
              multiple
              accept="application/pdf,image/png,image/jpeg,image/webp"
              style={{ display: "none" }}
              onChange={(e) => {
                const files = Array.from(e.target.files ?? []);
                if (files.length > 0) {
                  setUploadCount(files.length);
                  upload.mutate(files);
                }
                e.target.value = ""; // allow re-selecting the same file(s)
              }}
            />
            <button
              className="btn ghost"
              style={{ width: "100%", marginTop: 8, justifyContent: "center" }}
              disabled={upload.isPending}
              onClick={() => fileRef.current?.click()}
            >
              {upload.isPending ? `↑ ${uploadCount}…` : `+ ${t("files.add")}`}
            </button>
          </div>
          <div>
            <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 6 }}>{selected?.originalFilename ?? t("files.preview")}</div>
            <div style={{ border: "1px solid var(--border)", borderRadius: 10, overflow: "hidden", height: 560, background: "#525659" }}>
              {blobUrl && selected?.contentType?.startsWith("image/") && (
                <img src={blobUrl} alt={selected.originalFilename} style={{ width: "100%", height: "100%", objectFit: "contain" }} />
              )}
              {blobUrl && selected?.contentType === "application/pdf" && (
                <iframe title="preview" src={blobUrl} style={{ width: "100%", height: "100%", border: "none" }} />
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
    {paymentsFor && (
      <InvoicePaymentsModal companyId={companyId} period={period} documentId={paymentsFor}
        onClose={() => setPaymentsFor(null)} />
    )}
    </>
  );
}
