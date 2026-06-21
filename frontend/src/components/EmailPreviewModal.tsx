import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation } from "@tanstack/react-query";
import { taxPaymentsApi } from "../api/taxes";
import { emailApi } from "../api/email";
import { ApiError } from "../lib/apiClient";
import { Icon } from "./Icon";

const money = (n: number) => n.toLocaleString("ro-RO", { minimumFractionDigits: 0 });

export interface PreviewTarget {
  companyId: string;
  companyName: string;
  declarationIds: string[];
}

interface Draft { recipient: string; body: string; total: number; loading: boolean; sent: boolean; error?: string }

/** Bulk email preview before sending — one card per company, generated from its declarations. */
export function EmailPreviewModal({ targets, period, onClose, onSent }:
  { targets: PreviewTarget[]; period: string; onClose: () => void; onSent: () => void }) {
  const { t } = useTranslation();
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [sending, setSending] = useState(false);

  // Generate each company's body once on open.
  useEffect(() => {
    let cancelled = false;
    setDrafts(Object.fromEntries(targets.map((x) => [x.companyId, { recipient: "", body: "", total: 0, loading: true, sent: false }])));
    targets.forEach((x) => {
      Promise.all([taxPaymentsApi.previewEmail(x.companyId, x.declarationIds), emailApi.envelope(x.companyId)])
        .then(([p, env]) => { if (!cancelled) setDrafts((d) => ({ ...d, [x.companyId]: { ...d[x.companyId], body: p.body ?? "", total: p.total, recipient: env.recipient ?? "", loading: false } })); })
        .catch((e) => { if (!cancelled) setDrafts((d) => ({ ...d, [x.companyId]: { ...d[x.companyId], loading: false, error: e instanceof ApiError ? e.message : "Failed" } })); });
    });
    return () => { cancelled = true; };
  }, [targets]);

  const sendOne = useMutation({
    mutationFn: (v: { companyId: string; declarationIds: string[]; recipient: string; body: string }) =>
      taxPaymentsApi.sendEmail(v.companyId, { period, declarationIds: v.declarationIds, recipient: v.recipient, body: v.body }),
  });

  const sendAll = async () => {
    setSending(true);
    for (const x of targets) {
      const d = drafts[x.companyId];
      if (!d || d.sent || !d.body.trim()) continue;
      try {
        await sendOne.mutateAsync({ companyId: x.companyId, declarationIds: x.declarationIds, recipient: d.recipient, body: d.body });
        setDrafts((p) => ({ ...p, [x.companyId]: { ...p[x.companyId], sent: true } }));
      } catch (e) {
        setDrafts((p) => ({ ...p, [x.companyId]: { ...p[x.companyId], error: e instanceof ApiError ? e.message : "Send failed" } }));
      }
    }
    setSending(false);
    onSent();
  };

  const allSent = targets.every((x) => drafts[x.companyId]?.sent);

  return (
    <div style={overlay} onClick={onClose}>
      <div style={modal} onClick={(e) => e.stopPropagation()}>
        <div style={header}>
          <div>
            <div style={{ color: "var(--chrome-muted)", fontSize: 11 }}>{t("taxes.reviewBeforeSend")}</div>
            <div style={{ color: "#f3f8f7", fontSize: 17, fontWeight: 700 }}>{t("taxes.emailPreviewN", { n: targets.length })}</div>
            <div style={{ color: "var(--chrome-text)", fontSize: 12 }}>{t("taxes.onePerCompany")}</div>
          </div>
          <button onClick={onClose} style={closeBtn}><Icon name="x" size={16} /></button>
        </div>

        <div style={{ padding: 16, overflowY: "auto", background: "var(--bg)" }}>
          {targets.map((x) => {
            const d = drafts[x.companyId];
            return (
              <div key={x.companyId} style={card}>
                <div style={cardHead}>
                  <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    <div style={mailIcon}><Icon name="mail" size={13} style={{ color: "var(--teal-chip-fg)" }} /></div>
                    <b style={{ fontSize: 13 }}>{x.companyName}</b>
                    {d?.sent && <span className="pill ok round">✓</span>}
                  </div>
                  {d && !d.loading && <span className="pill teal round mono">{money(d.total)}</span>}
                </div>
                <div style={{ padding: 12 }}>
                  {d?.loading && <p style={{ color: "var(--text-muted)" }}>{t("common.loading")}</p>}
                  {d?.error && <p style={{ color: "var(--danger-fg)" }}>{d.error}</p>}
                  {d && !d.loading && (
                    <>
                      <input placeholder={t("taxes.recipient")} value={d.recipient}
                        onChange={(e) => setDrafts((p) => ({ ...p, [x.companyId]: { ...p[x.companyId], recipient: e.target.value } }))}
                        style={input} disabled={d.sent} />
                      <textarea value={d.body} disabled={d.sent}
                        onChange={(e) => setDrafts((p) => ({ ...p, [x.companyId]: { ...p[x.companyId], body: e.target.value } }))}
                        style={{ ...input, minHeight: 150, marginTop: 8, fontFamily: "inherit", resize: "vertical" }} />
                    </>
                  )}
                </div>
              </div>
            );
          })}
        </div>

        <div style={footer}>
          <span style={{ color: "var(--text-muted)", fontSize: 11.5 }}>{t("taxes.eachLogged")}</span>
          <div style={{ display: "flex", gap: 8 }}>
            <button onClick={onClose}>{t("common.cancel")}</button>
            <button className="primary" onClick={sendAll} disabled={sending || allSent}>
              <Icon name="mail" size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />
              {sending ? t("taxes.sending") : t("email.sendN", { n: targets.length })}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

const overlay: React.CSSProperties = { position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex", alignItems: "flex-start", justifyContent: "center", padding: "4vh 16px", zIndex: 60 };
const modal: React.CSSProperties = { background: "var(--surface)", borderRadius: 14, width: "min(660px, 96vw)", maxHeight: "88vh", display: "flex", flexDirection: "column", overflow: "hidden", boxShadow: "var(--shadow-modal)" };
const header: React.CSSProperties = { display: "flex", justifyContent: "space-between", alignItems: "flex-start", background: "var(--chrome-bg)", padding: "12px 16px" };
const closeBtn: React.CSSProperties = { background: "none", border: "none", color: "var(--chrome-text)", cursor: "pointer" };
const card: React.CSSProperties = { border: "1px solid var(--border)", borderRadius: 12, background: "var(--surface)", marginBottom: 12, overflow: "hidden" };
const cardHead: React.CSSProperties = { display: "flex", justifyContent: "space-between", alignItems: "center", padding: "10px 12px", borderBottom: "1px solid var(--hair)" };
const mailIcon: React.CSSProperties = { width: 26, height: 26, borderRadius: 7, background: "var(--teal-chip-bg)", display: "grid", placeItems: "center" };
const input: React.CSSProperties = { width: "100%", boxSizing: "border-box", padding: "7px 9px", border: "1px solid var(--border)", borderRadius: 8, fontSize: 13 };
const footer: React.CSSProperties = { display: "flex", justifyContent: "space-between", alignItems: "center", padding: "12px 16px", borderTop: "1px solid var(--border)" };
