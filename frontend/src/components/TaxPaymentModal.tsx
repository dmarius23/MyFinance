import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { taxPaymentsApi, type EmailView } from "../api/taxes";
import { documentsApi } from "../api/documents";
import { ApiError } from "../lib/apiClient";

const money = (n: number) => n.toLocaleString("ro-RO");
const when = (iso: string) => new Date(iso).toLocaleString("ro-RO");

/** MOD-07 — declarations, payment breakdown, and the compose → send → history email workflow. */
export function TaxPaymentModal({ companyId, companyName, period, onClose }:
  { companyId: string; companyName: string; period: string; onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const fileRef = useRef<HTMLInputElement>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  // Compose editor state (open when composing/resending).
  const [compose, setCompose] = useState<{ declarationIds: string[]; recipient: string; body: string } | null>(null);
  const [composeError, setComposeError] = useState<string | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ["tax-payments", companyId, period],
    queryFn: () => taxPaymentsApi.summary(companyId, period),
  });

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["tax-payments", companyId, period] });
    void qc.invalidateQueries({ queryKey: ["doc-summary", period] });
  };

  const upload = useMutation({
    mutationFn: (file: File) => documentsApi.upload(companyId, period, file),
    onSuccess: () => { setUploadError(null); refresh(); },
    onError: (e) => setUploadError(e instanceof ApiError ? e.message : "Upload failed"),
  });

  const preview = useMutation({
    mutationFn: (ids: string[]) => taxPaymentsApi.previewEmail(companyId, ids),
  });

  const send = useMutation({
    mutationFn: (c: { declarationIds: string[]; recipient: string; body: string }) =>
      taxPaymentsApi.sendEmail(companyId, { period, ...c }),
    onSuccess: () => { setCompose(null); setComposeError(null); refresh(); },
    onError: (e) => setComposeError(e instanceof ApiError ? e.message : "Send failed"),
  });

  const decls = data?.declarations ?? [];
  const allSelected = decls.length > 0 && decls.every((d) => selected.has(d.id));
  const toggle = (id: string) => setSelected((s) => {
    const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n;
  });
  const toggleAll = () => setSelected(allSelected ? new Set() : new Set(decls.map((d) => d.id)));

  const openCompose = (ids: string[]) => {
    if (ids.length === 0) return;
    setComposeError(null);
    preview.mutate(ids, {
      onSuccess: (p) => setCompose({ declarationIds: ids, recipient: "", body: p.body ?? "" }),
    });
  };
  const resend = (e: EmailView) => {
    setComposeError(null);
    setCompose({ declarationIds: e.declarationIds, recipient: e.recipient ?? "", body: e.body });
  };

  const onPick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0]; if (f) upload.mutate(f); e.target.value = "";
  };

  return (
    <div style={overlay} onClick={onClose}>
      <div style={modal} onClick={(e) => e.stopPropagation()}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
          <h2 style={{ margin: 0 }}>{t("taxes.title")} — {companyName}</h2>
          <button onClick={onClose} style={{ border: "none", background: "none", fontSize: 18, cursor: "pointer" }}>✕</button>
        </div>

        {isLoading && <p>{t("common.loading")}</p>}
        {error && <p style={{ color: "#dc2626" }}>{error instanceof ApiError ? error.message : "Failed to load"}</p>}

        {data && (
          <>
            {/* Declarations with selection */}
            <h3 style={h3}>{t("taxes.declarations")}</h3>
            {decls.length === 0 ? (
              <p style={{ color: "var(--text-muted)" }}>{t("taxes.noDeclarations")}</p>
            ) : (
              <table style={table}>
                <thead><tr style={th}>
                  <td style={{ ...td, width: 28 }}>
                    <input type="checkbox" checked={allSelected} onChange={toggleAll} title={t("taxes.selectAll")} />
                  </td>
                  <td style={td}>{t("taxes.form")}</td>
                  <td style={{ ...td, textAlign: "right" }}>{t("taxes.amount")}</td>
                  <td style={td}>{t("taxes.sent")}</td>
                </tr></thead>
                <tbody>
                  {decls.map((d) => (
                    <tr key={d.id} style={{ borderTop: "1px solid var(--border)", opacity: d.duplicate ? 0.55 : 1 }}>
                      <td style={td}>
                        <input type="checkbox" checked={selected.has(d.id) && !d.duplicate}
                          disabled={d.duplicate} title={d.duplicate ? t("taxes.duplicateTip") : ""}
                          onChange={() => toggle(d.id)} />
                      </td>
                      <td style={td}>
                        <b>{d.type}</b>
                        {d.duplicate && <span title={t("taxes.duplicateTip")}
                          style={{ marginLeft: 6, fontSize: 11, color: "#6b7280" }}>· {t("taxes.duplicate")}</span>}
                      </td>
                      <td style={{ ...td, textAlign: "right" }}>
                        {money(d.computedTotal)}
                        {d.mismatch && <span title={t("taxes.mismatchTip", { declared: d.declaredTotal })} style={{ marginLeft: 6, color: "#b45309" }}>⚠</span>}
                      </td>
                      <td style={{ ...td, fontSize: 12, color: "var(--text-muted)" }}>
                        {d.sentCount > 0 ? t("taxes.sentTimes", { n: d.sentCount }) + (d.lastSentAt ? ` · ${when(d.lastSentAt)}` : "") : "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {decls.length > 0 && (
              <div style={{ marginTop: 8 }}>
                <button className="primary" disabled={selected.size === 0 || preview.isPending}
                  onClick={() => openCompose([...selected])}>
                  ✉ {t("taxes.composeSelected", { n: selected.size })}
                </button>
              </div>
            )}

            {/* Payment lines */}
            <h3 style={h3}>{t("taxes.toPay")}</h3>
            {data.paymentLines.length === 0 ? (
              <p style={{ color: "var(--text-muted)" }}>{t("taxes.noLines")}</p>
            ) : (
              <table style={table}>
                <thead><tr style={th}>
                  <td style={td}>{t("taxes.explanation")}</td><td style={td}>IBAN</td>
                  <td style={td}>{t("taxes.due")}</td><td style={{ ...td, textAlign: "right" }}>{t("taxes.amount")}</td>
                </tr></thead>
                <tbody>
                  {data.paymentLines.map((l, i) => (
                    <tr key={i} style={{ borderTop: "1px solid var(--border)" }}>
                      <td style={td}>{l.explanation}</td>
                      <td style={{ ...td, fontFamily: "monospace", fontSize: 12 }}>{l.iban}</td>
                      <td style={td}>{l.scadenta ?? "—"}</td>
                      <td style={{ ...td, textAlign: "right" }}><b>{money(l.amount)}</b></td>
                    </tr>
                  ))}
                  <tr style={{ borderTop: "2px solid var(--border)" }}>
                    <td style={td} colSpan={3}><b>{t("taxes.total")}</b></td>
                    <td style={{ ...td, textAlign: "right" }}><b>{money(data.totalToPay)}</b></td>
                  </tr>
                </tbody>
              </table>
            )}
            {data.unconfigured.length > 0 && (
              <div style={warn}>⚠ {t("taxes.unconfigured")}:{" "}
                {data.unconfigured.map((u) => `${u.category} (${money(u.amount)})`).join(", ")}. {t("taxes.setInSettings")}</div>
            )}

            {/* Compose editor */}
            {compose && (
              <div style={{ marginTop: 14, border: "1px solid var(--primary)", borderRadius: 10, padding: 12 }}>
                <h3 style={{ ...h3, marginTop: 0 }}>{t("taxes.composeTitle", { n: compose.declarationIds.length })}</h3>
                <input placeholder={t("taxes.recipient")} value={compose.recipient}
                  onChange={(e) => setCompose({ ...compose, recipient: e.target.value })}
                  style={{ width: "100%", boxSizing: "border-box", marginBottom: 8 }} />
                <textarea value={compose.body} onChange={(e) => setCompose({ ...compose, body: e.target.value })}
                  style={{ width: "100%", minHeight: 240, fontFamily: "inherit", fontSize: 13, boxSizing: "border-box" }} />
                {composeError && <p style={{ color: "#dc2626" }}>{composeError}</p>}
                <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 8 }}>
                  <button onClick={() => setCompose(null)}>{t("common.cancel")}</button>
                  <button className="primary" disabled={send.isPending || !compose.body.trim()}
                    onClick={() => send.mutate(compose)}>
                    {send.isPending ? t("taxes.sending") : t("taxes.send")}
                  </button>
                </div>
              </div>
            )}

            {/* Send history */}
            <h3 style={h3}>{t("taxes.history")}</h3>
            {data.emails.length === 0 ? (
              <p style={{ color: "var(--text-muted)" }}>{t("taxes.noEmails")}</p>
            ) : (
              <table style={table}>
                <thead><tr style={th}>
                  <td style={td}>{t("taxes.when")}</td><td style={td}>{t("taxes.recipient")}</td>
                  <td style={td}>{t("taxes.status")}</td><td style={td} />
                </tr></thead>
                <tbody>
                  {data.emails.map((e) => (
                    <tr key={e.id} style={{ borderTop: "1px solid var(--border)" }}>
                      <td style={{ ...td, fontSize: 12 }}>{when(e.sentAt)}</td>
                      <td style={{ ...td, fontSize: 12 }}>{e.recipient ?? "—"} · {e.declarationIds.length} {t("taxes.decl")}</td>
                      <td style={td}>
                        <span style={{ color: e.status === "SENT" ? "#166534" : "#991b1b", fontSize: 12 }}>{e.status}</span>
                      </td>
                      <td style={{ ...td, textAlign: "right" }}>
                        <button onClick={() => resend(e)}>{t("taxes.resend")}</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            {/* Upload */}
            <div style={{ marginTop: 12, borderTop: "1px solid var(--border)", paddingTop: 10 }}>
              {uploadError && <p style={{ color: "#dc2626" }}>{uploadError}</p>}
              <input ref={fileRef} type="file" accept="application/pdf" onChange={onPick} style={{ display: "none" }} />
              <button onClick={() => fileRef.current?.click()} disabled={upload.isPending}>
                {upload.isPending ? t("taxes.uploading") : t("taxes.uploadDeclaration")}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex",
  alignItems: "flex-start", justifyContent: "center", padding: "5vh 16px", zIndex: 50, overflowY: "auto",
};
const modal: React.CSSProperties = { background: "var(--card-bg, #fff)", borderRadius: 12, padding: 20, width: "min(780px, 100%)" };
const h3: React.CSSProperties = { margin: "16px 0 6px", fontSize: 14 };
const table: React.CSSProperties = { width: "100%", borderCollapse: "collapse" };
const th: React.CSSProperties = { textAlign: "left", color: "var(--text-muted)" };
const td: React.CSSProperties = { padding: 6 };
const warn: React.CSSProperties = { marginTop: 10, padding: "8px 12px", borderRadius: 8, background: "#fef3c7", color: "#92400e", fontSize: 13 };
