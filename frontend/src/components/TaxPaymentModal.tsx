import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { taxPaymentsApi, type EmailView } from "../api/taxes";
import { documentsApi } from "../api/documents";
import { emailApi } from "../api/email";
import { ApiError } from "../lib/apiClient";
import { Icon } from "./Icon";

const money = (n: number) => n.toLocaleString("ro-RO", { minimumFractionDigits: 0 });
const when = (iso: string) => new Date(iso).toLocaleDateString("ro-RO", { day: "numeric", month: "short" });
const time = (iso: string) => new Date(iso).toLocaleTimeString("ro-RO", { hour: "2-digit", minute: "2-digit" });

/** MOD-07 — tax payment workspace (B skin): declarations + compose + to-pay (left), email history (right). */
export function TaxPaymentModal({ companyId, companyName, period, onClose }:
  { companyId: string; companyName: string; period: string; onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const fileRef = useRef<HTMLInputElement>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [compose, setCompose] = useState<{ declarationIds: string[]; recipient: string; body: string } | null>(null);
  const [composeError, setComposeError] = useState<string | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ["tax-payments", companyId, period],
    queryFn: () => taxPaymentsApi.summary(companyId, period),
  });

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["tax-payments", companyId, period] });
    void qc.invalidateQueries({ queryKey: ["tax-list", period] });
  };

  const upload = useMutation({
    mutationFn: (file: File) => documentsApi.upload(companyId, period, file),
    onSuccess: () => { setUploadError(null); refresh(); },
    onError: (e) => setUploadError(e instanceof ApiError ? e.message : "Upload failed"),
  });
  const preview = useMutation({ mutationFn: (ids: string[]) => taxPaymentsApi.previewEmail(companyId, ids) });
  const send = useMutation({
    mutationFn: (c: { declarationIds: string[]; recipient: string; body: string }) =>
      taxPaymentsApi.sendEmail(companyId, { period, ...c }),
    onSuccess: () => { setCompose(null); setComposeError(null); refresh(); },
    onError: (e) => setComposeError(e instanceof ApiError ? e.message : "Send failed"),
  });

  const decls = data?.declarations ?? [];
  const toggle = (id: string) => setSelected((s) => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });
  const openCompose = (ids: string[]) => {
    if (!ids.length) return;
    setComposeError(null);
    preview.mutate(ids, {
      onSuccess: (p) => {
        setCompose({ declarationIds: ids, recipient: "", body: p.body ?? "" });
        // Prefill the company's representative as recipient.
        emailApi.envelope(companyId)
          .then((env) => setCompose((c) => (c && !c.recipient ? { ...c, recipient: env.recipient ?? "" } : c)))
          .catch(() => undefined);
      },
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
        {/* dark header */}
        <div style={header}>
          <div>
            <div style={{ color: "var(--chrome-muted)", fontSize: 11 }}>{t("taxes.title")}</div>
            <div style={{ color: "#f3f8f7", fontSize: 17, fontWeight: 700 }}>{companyName}</div>
          </div>
          <button onClick={onClose} style={closeBtn}><Icon name="x" size={16} /></button>
        </div>

        <div style={{ padding: 16, overflowY: "auto" }}>
          {isLoading && <p>{t("common.loading")}</p>}
          {error && <p style={{ color: "var(--danger-fg)" }}>{error instanceof ApiError ? error.message : "Failed to load"}</p>}

          {data && (
            <div style={{ display: "grid", gridTemplateColumns: "1.35fr 1fr", gap: 16, alignItems: "start" }}>
              {/* LEFT */}
              <div style={{ display: "grid", gap: 14 }}>
                {/* Declarations */}
                <section style={panel}>
                  <div style={panelHead}>
                    <b>{t("taxes.declarations")}</b>
                    <span style={{ color: "var(--text-muted)", fontSize: 11.5 }}>{t("taxes.selectToCompose")}</span>
                  </div>
                  {decls.length === 0 ? (
                    <div style={empty}>{t("taxes.noDeclarations")}</div>
                  ) : (
                    <>
                      <div style={{ ...declRow, ...thRow }}>
                        <div /><div>{t("taxes.form")}</div>
                        <div style={{ textAlign: "right" }}>{t("taxes.amount")}</div>
                        <div style={{ textAlign: "right" }}>{t("taxes.sent")}</div>
                      </div>
                      {decls.map((d) => (
                        <div key={d.id} style={{ ...declRow, opacity: d.duplicate ? 0.5 : 1 }}>
                          <input type="checkbox" checked={selected.has(d.id) && !d.duplicate} disabled={d.duplicate}
                            title={d.duplicate ? t("taxes.duplicateTip") : ""} onChange={() => toggle(d.id)} />
                          <div><b>{d.type}</b>{d.duplicate && <span style={{ color: "var(--text-muted)", fontSize: 11 }}> · {t("taxes.duplicate")}</span>}</div>
                          <div className="mono" style={{ textAlign: "right" }}>
                            {money(d.computedTotal)}{d.mismatch && <span title={t("taxes.mismatchTip", { declared: d.declaredTotal })} style={{ color: "#b45309" }}> ⚠</span>}
                          </div>
                          <div style={{ textAlign: "right", fontSize: 11, color: "var(--text-secondary)" }}>
                            {d.sentCount > 0 ? `${d.sentCount}× · ${d.lastSentAt ? when(d.lastSentAt) : ""}` : "—"}
                          </div>
                        </div>
                      ))}
                      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginTop: 8 }}>
                        <span style={{ color: "var(--text-muted)", fontSize: 12 }}>{selected.size} {t("taxes.selected")}</span>
                        <button className="primary" disabled={selected.size === 0 || preview.isPending}
                          onClick={() => openCompose([...selected])}>
                          <Icon name="mail" size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />
                          {t("taxes.composeSelected", { n: selected.size })}
                        </button>
                      </div>
                    </>
                  )}
                </section>

                {/* Compose */}
                {compose && (
                  <section style={{ border: "1px solid var(--teal-chip-bd)", borderRadius: 11, overflow: "hidden", boxShadow: "0 6px 22px rgba(20,184,166,0.10)" }}>
                    <div style={{ ...header, padding: "10px 12px" }}>
                      <span style={{ color: "#f3f8f7", fontWeight: 600, fontSize: 13 }}>
                        {t("taxes.composeTitle", { n: compose.declarationIds.length })}
                      </span>
                      <button onClick={() => setCompose(null)} style={closeBtn}><Icon name="x" size={15} /></button>
                    </div>
                    <div style={{ padding: 12 }}>
                      <input placeholder={t("taxes.recipient")} value={compose.recipient}
                        onChange={(e) => setCompose({ ...compose, recipient: e.target.value })} style={input} />
                      <textarea value={compose.body} onChange={(e) => setCompose({ ...compose, body: e.target.value })}
                        style={{ ...input, minHeight: 200, fontFamily: "inherit", marginTop: 8, resize: "vertical" }} />
                      {composeError && <p style={{ color: "var(--danger-fg)" }}>{composeError}</p>}
                      <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 8 }}>
                        <button onClick={() => setCompose(null)}>{t("common.cancel")}</button>
                        <button className="primary" disabled={send.isPending || !compose.body.trim()}
                          onClick={() => send.mutate(compose)}>{send.isPending ? t("taxes.sending") : t("taxes.send")}</button>
                      </div>
                    </div>
                  </section>
                )}

                {/* To pay */}
                <section style={panel}>
                  <div style={panelHead}><b>{t("taxes.toPay")}</b></div>
                  {data.paymentLines.length === 0 ? (
                    <div style={empty}>{t("taxes.noLines")}</div>
                  ) : (
                    <>
                      <div style={{ ...payRow, ...thRow }}>
                        <div>{t("taxes.explanation")}</div><div>IBAN</div>
                        <div>{t("taxes.due")}</div><div style={{ textAlign: "right" }}>{t("taxes.amount")}</div>
                      </div>
                      {data.paymentLines.map((l, i) => (
                        <div key={i} style={payRow}>
                          <div>{l.explanation}</div>
                          <div className="mono" style={{ fontSize: 11, color: "var(--text-secondary)" }}>{l.iban}</div>
                          <div className="mono" style={{ fontSize: 11 }}>{l.scadenta ? when(l.scadenta) : "—"}</div>
                          <div className="mono" style={{ textAlign: "right", fontWeight: 600 }}>{money(l.amount)}</div>
                        </div>
                      ))}
                      <div style={{ ...payRow, background: "var(--bg)", fontWeight: 700 }}>
                        <div>{t("taxes.total")}</div><div /><div />
                        <div className="mono" style={{ textAlign: "right", fontSize: 13 }}>{money(data.totalToPay)}</div>
                      </div>
                    </>
                  )}
                  {data.unconfigured.length > 0 && (
                    <div className="pill warn" style={{ display: "block", marginTop: 10, padding: "8px 12px", borderRadius: 8, fontSize: 11.5 }}>
                      ⚠ {t("taxes.unconfigured")}: {data.unconfigured.map((u) => `${u.category} (${money(u.amount)})`).join(", ")}. {t("taxes.setInSettings")}
                    </div>
                  )}
                </section>

                {/* Upload */}
                <div>
                  {uploadError && <p style={{ color: "var(--danger-fg)" }}>{uploadError}</p>}
                  <input ref={fileRef} type="file" accept="application/pdf" onChange={onPick} style={{ display: "none" }} />
                  <button onClick={() => fileRef.current?.click()} disabled={upload.isPending}>
                    <Icon name="upload" size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />
                    {upload.isPending ? t("taxes.uploading") : t("taxes.uploadDeclaration")}
                  </button>
                </div>
              </div>

              {/* RIGHT — email history */}
              <section style={panel}>
                <div style={panelHead}>
                  <b>{t("taxes.history")}</b>
                  {data.emails.length > 0 && <span className="pill teal round">{data.emails.length}</span>}
                </div>
                {data.emails.length === 0 ? (
                  <div style={empty}>{t("taxes.noEmails")}</div>
                ) : (
                  data.emails.map((e) => (
                    <div key={e.id} style={{ display: "flex", gap: 10, padding: "11px 0", borderTop: "1px solid var(--hair)" }}>
                      <div style={mailIcon}><Icon name="mail" size={14} style={{ color: "var(--teal-chip-fg)" }} /></div>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontSize: 12.5, fontWeight: 600 }}>{e.declarationIds.length} {t("taxes.decl")}</div>
                        <div className="mono" style={{ fontSize: 11, color: "var(--text-muted)" }}>{t("taxes.to")} {e.recipient ?? "—"}</div>
                        <button onClick={() => resend(e)} style={{ ...linkBtn, marginTop: 3 }}>{t("taxes.resend")}</button>
                      </div>
                      <div style={{ textAlign: "right" }}>
                        <div className="mono" style={{ fontSize: 12, fontWeight: 600 }}>{when(e.sentAt)}</div>
                        <div className="mono" style={{ fontSize: 11, color: "var(--text-muted)" }}>{time(e.sentAt)}</div>
                        <span className={`pill round ${e.status === "SENT" ? "ok" : "danger"}`} style={{ marginTop: 3, display: "inline-block", fontSize: 9.5 }}>{e.status}</span>
                      </div>
                    </div>
                  ))
                )}
              </section>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex",
  alignItems: "flex-start", justifyContent: "center", padding: "4vh 16px", zIndex: 50, overflowY: "auto",
};
const modal: React.CSSProperties = {
  background: "var(--surface)", borderRadius: 14, width: "min(1120px, 97vw)", maxHeight: "90vh",
  display: "flex", flexDirection: "column", overflow: "hidden", boxShadow: "var(--shadow-modal)",
};
const header: React.CSSProperties = {
  display: "flex", justifyContent: "space-between", alignItems: "center",
  background: "var(--chrome-bg)", padding: "12px 16px",
};
const closeBtn: React.CSSProperties = { background: "none", border: "none", color: "var(--chrome-text)", cursor: "pointer" };
const panel: React.CSSProperties = { border: "1px solid var(--border)", borderRadius: 11, padding: 12 };
const panelHead: React.CSSProperties = { display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8 };
const empty: React.CSSProperties = { color: "var(--text-muted)", fontSize: 12.5, padding: "6px 0" };
const declRow: React.CSSProperties = { display: "grid", gridTemplateColumns: "20px 1fr 110px 90px", alignItems: "center", gap: 8, padding: "6px 0", borderTop: "1px solid var(--hair)" };
const payRow: React.CSSProperties = { display: "grid", gridTemplateColumns: "1.4fr 1.6fr 60px 90px", alignItems: "center", gap: 8, padding: "7px 4px", borderTop: "1px solid var(--hair)", fontSize: 12.5 };
const thRow: React.CSSProperties = { borderTop: "none", background: "var(--th-bg-sub)", fontSize: 9.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--text-muted)" };
const input: React.CSSProperties = { width: "100%", boxSizing: "border-box", padding: "7px 9px", border: "1px solid var(--border)", borderRadius: 8, fontSize: 13 };
const mailIcon: React.CSSProperties = { width: 30, height: 30, flex: "none", borderRadius: 8, background: "var(--teal-chip-bg)", display: "grid", placeItems: "center" };
const linkBtn: React.CSSProperties = { background: "none", border: "none", color: "var(--primary-dark)", cursor: "pointer", padding: 0, fontSize: 12 };
