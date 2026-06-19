import { Fragment, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { documentsApi, DOCUMENT_TYPES, type Document } from "../api/documents";
import { reconciliationApi } from "../api/bank";
import { InvoicePaymentsModal } from "./InvoicePaymentsModal";

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(15,23,42,0.4)",
  display: "grid", placeItems: "center", zIndex: 50,
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
  // Bank statements first, then a divider, then invoices/receipts (stable within each group).
  const ordered = [...data].sort((a, b) =>
    (a.type === "BANK_STATEMENT" ? 0 : 1) - (b.type === "BANK_STATEMENT" ? 0 : 1));
  const [selId, setSelId] = useState<string | null>(null);
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [uploadCount, setUploadCount] = useState(0);
  const [paymentsFor, setPaymentsFor] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const selected: Document | undefined = data.find((d) => d.id === selId) ?? data[0];

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
      <div className="card" style={{ width: 1340, maxWidth: "97vw", maxHeight: "92vh", overflow: "auto" }} onClick={(e) => e.stopPropagation()}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h2 style={{ margin: 0 }}>{t("files.title")} — {companyName}</h2>
          <div style={{ display: "flex", gap: 8 }}>
            <button onClick={() => reclassify.mutate()} disabled={reclassify.isPending} title={t("files.rescanHint")}>
              {reclassify.isPending ? "…" : `↻ ${t("files.rescan")}`}
            </button>
            <button onClick={onClose}>✕</button>
          </div>
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "460px 1fr", gap: 14, marginTop: 12, alignItems: "start" }}>
          <div>
            <div style={{ maxHeight: 560, overflow: "auto" }}>
              {data.length === 0 && <div style={{ color: "var(--text-muted)" }}>{t("files.none")}</div>}
              {ordered.map((d, i) => {
                const st = statusByDoc.get(d.id);
                const isStmt = d.type === "BANK_STATEMENT";
                const showHeader = i === 0 || (ordered[i - 1].type === "BANK_STATEMENT") !== isStmt;
                const isInvoice = d.type === "INVOICE" || d.type === "RECEIPT";
                const pay = st?.paymentStatus; // UNPAID | PARTIAL | PAID | null
                const payStyle = pay === "PAID" ? { bg: "#dcfce7", bd: "#16a34a" }
                  : pay === "PARTIAL" ? { bg: "#fef3c7", bd: "#d97706" }
                  : { bg: "#fee2e2", bd: "#dc2626" }; // UNPAID / no association
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
                    <div style={{ fontWeight: 600, fontSize: 12.5, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                      {st?.warning && (
                        <span title={t(`doc.warn.${st.warningReason}`, { defaultValue: "" })}
                          style={{ color: "#d97706", marginRight: 4 }}>⚠</span>
                      )}
                      {d.originalFilename}
                    </div>
                    <div style={{ display: "flex", alignItems: "center", gap: 6, marginTop: 3 }}>
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
                      {st?.duplicate && (
                        <span title={t("doc.warn.duplicate")}
                          style={{ background: "#fee2e2", color: "#b91c1c", border: "1px solid #fecaca",
                            borderRadius: 999, padding: "1px 7px", fontSize: 10, fontWeight: 600, textTransform: "uppercase" }}>
                          {t("doc.duplicateChip")}
                        </span>
                      )}
                      {st?.wrongParty && (
                        <span title={t("doc.warn.wrongParty")}
                          style={{ background: "#fef2f2", color: "#991b1b", border: "1px solid #fecaca",
                            borderRadius: 999, padding: "1px 7px", fontSize: 10, fontWeight: 600, textTransform: "uppercase" }}>
                          {t("doc.wrongPartyChip")}
                        </span>
                      )}
                    </div>
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
                    <button onClick={(e) => { e.stopPropagation(); remove.mutate(d.id); }} title="Delete"
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
