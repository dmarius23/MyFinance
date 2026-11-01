import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { whatsappApi, type WhatsAppAttachment, type WhatsAppKind } from "../api/whatsapp";
import { ApiError } from "../lib/apiClient";
import { useWhatsAppConfigured } from "../lib/useWhatsAppConfigured";
import { useWhatsAppMode } from "../lib/useWhatsAppMode";
import { waMeUrl } from "../lib/whatsapp";
import { Icon } from "./Icon";
import { MissingInfoWarning } from "./MissingInfoWarning";

export interface WhatsAppTarget {
  companyId: string;
  companyName: string;
  attachments?: WhatsAppAttachment[];
}

interface Draft { phone: string; body: string; loading: boolean; sent: boolean; excluded: string[]; error?: string }

/**
 * Bulk WhatsApp preview before sending — one card per company, phone + standard body prefilled and
 * editable (mirrors the bulk-email modal). "Send N" fires each message via the same per-company endpoint
 * used by the single WhatsApp composer, marking each card sent/failed. Delivery goes through the backend
 * WhatsApp port (Twilio when configured).
 */
export function WhatsAppBulkModal({ targets, kind, period, loadBody, onClose, onSent }:
  { targets: WhatsAppTarget[]; kind: WhatsAppKind; period: string;
    loadBody: (companyId: string) => Promise<string>; onClose: () => void; onSent?: () => void }) {
  const { t } = useTranslation();
  const whatsappConfigured = useWhatsAppConfigured();
  const clickToChat = useWhatsAppMode() === "CLICK_TO_CHAT";
  const qc = useQueryClient();
  const [drafts, setDrafts] = useState<Record<string, Draft>>(
    () => Object.fromEntries(targets.map((x) => [x.companyId, { phone: "", body: "", loading: true, sent: false, excluded: [] }])),
  );
  const [sending, setSending] = useState(false);

  // Prefill each company's recipient phone + standard body once on open.
  useEffect(() => {
    let cancelled = false;
    targets.forEach((x) => {
      Promise.all([whatsappApi.recipient(x.companyId), loadBody(x.companyId)])
        .then(([r, body]) => { if (!cancelled) setDrafts((d) => ({ ...d, [x.companyId]: { ...d[x.companyId], phone: r.phone ?? "", body, loading: false } })); })
        .catch((e) => { if (!cancelled) setDrafts((d) => ({ ...d, [x.companyId]: { ...d[x.companyId], loading: false, error: e instanceof ApiError ? e.message : "Failed" } })); });
    });
    return () => { cancelled = true; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [targets, period]);

  const patch = (id: string, p: Partial<Draft>) => setDrafts((d) => ({ ...d, [id]: { ...d[id], ...p } }));

  const sendOne = useMutation({
    mutationFn: (v: { companyId: string; body: string; attachmentDocumentIds: string[] }) =>
      whatsappApi.send(v.companyId, { kind, period, body: v.body, attachmentDocumentIds: v.attachmentDocumentIds }),
  });
  const selectedFiles = (x: WhatsAppTarget, d: Draft) =>
    (x.attachments ?? []).filter((a) => !d.excluded.includes(a.id)).map((a) => a.id);
  const toggleFile = (id: string, fileId: string) => setDrafts((all) => {
    const d = all[id];
    const excluded = d.excluded.includes(fileId) ? d.excluded.filter((f) => f !== fileId) : [...d.excluded, fileId];
    return { ...all, [id]: { ...d, excluded } };
  });

  const sendAll = async () => {
    setSending(true);
    let anyError = false;
    for (const x of targets) {
      const d = drafts[x.companyId];
      if (!d || d.sent || d.loading || !d.body.trim() || !d.phone.trim()) continue;
      try {
        await sendOne.mutateAsync({ companyId: x.companyId, body: d.body, attachmentDocumentIds: selectedFiles(x, d) });
        patch(x.companyId, { sent: true, error: undefined });
        void qc.invalidateQueries({ queryKey: ["whatsapp", x.companyId, kind, period] });
      } catch (e) {
        anyError = true;
        patch(x.companyId, { error: e instanceof ApiError ? e.message : t("channel.sendFailed") });
      }
    }
    setSending(false);
    onSent?.();
    if (!anyError) onClose(); // all sent → close; keep open on failure to show the error
  };

  const sendableCount = targets.filter((x) => {
    const d = drafts[x.companyId];
    return d && !d.sent && !d.loading && d.body.trim() && d.phone.trim();
  }).length;
  const allSent = targets.every((x) => drafts[x.companyId]?.sent);

  return (
    <div style={overlay} onClick={onClose}>
      <div style={modal} onClick={(e) => e.stopPropagation()}>
        <div style={header}>
          <div>
            <div style={{ color: "var(--chrome-muted)", fontSize: 11 }}>{t("channel.reviewBeforeSend")}</div>
            <div style={{ color: "#f3f8f7", fontSize: 17, fontWeight: 700 }}>{t("channel.whatsappPreviewN", { n: targets.length })}</div>
          </div>
          <button onClick={onClose} style={closeBtn}><Icon name="x" size={16} /></button>
        </div>

        <div style={{ padding: 16, overflowY: "auto", background: "var(--bg)" }}>
          {targets.map((x) => {
            const d = drafts[x.companyId];
            const files = x.attachments ?? [];
            return (
              <div key={x.companyId} style={card}>
                <div style={cardHead}>
                  <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    <div style={waIcon}><Icon name="whatsapp" size={13} style={{ color: "#128C7E" }} /></div>
                    <b style={{ fontSize: 13 }}>{x.companyName}</b>
                    {d?.sent && <span className="pill ok round">✓</span>}
                    {d?.error && <span className="pill danger round" title={d.error}>{t("email.failed")}</span>}
                  </div>
                </div>
                <div style={{ padding: 12 }}>
                  {d?.loading && <p style={{ color: "var(--text-muted)" }}>{t("common.loading")}</p>}
                  {d && !d.loading && (
                    <>
                      <MissingInfoWarning problems={d.phone.trim() ? [] : [t("warn.noPhone")]} />
                      <input type="tel" placeholder="+40…" value={d.phone}
                        onChange={(e) => patch(x.companyId, { phone: e.target.value })}
                        style={input} disabled={d.sent} />
                      <textarea value={d.body} disabled={d.sent}
                        onChange={(e) => patch(x.companyId, { body: e.target.value })}
                        style={{ ...input, minHeight: 150, marginTop: 8, fontFamily: "inherit", resize: "vertical" }} />
                      {!clickToChat && files.length > 0 && (
                        <fieldset style={fileSet}>
                          <legend style={fileLegend}>
                            {t("channel.attachments")} ({selectedFiles(x, d).length}/{files.length})
                          </legend>
                          {files.map((a) => (
                            <label key={a.id} style={fileRow}>
                              <input type="checkbox" disabled={d.sent} checked={!d.excluded.includes(a.id)}
                                onChange={() => toggleFile(x.companyId, a.id)} />
                              <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{a.filename}</span>
                            </label>
                          ))}
                        </fieldset>
                      )}
                      {clickToChat && d.phone.trim() && (
                        <a href={waMeUrl(d.phone, d.body)} target="_blank" rel="noopener noreferrer"
                          style={{ ...waBtnPrimary, display: "inline-flex", alignItems: "center", marginTop: 8, textDecoration: "none" }}>
                          <Icon name="whatsapp" size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />{t("channel.openWhatsapp")}
                        </a>
                      )}
                    </>
                  )}
                </div>
              </div>
            );
          })}
        </div>

        <div style={footer}>
          <span style={{ color: "var(--text-muted)", fontSize: 11.5 }}>
            {clickToChat ? t("channel.openWhatsapp") : t("taxes.eachLogged")}
          </span>
          <div style={{ display: "flex", gap: 8 }}>
            <button onClick={onClose}>{t("common.cancel")}</button>
            {!clickToChat && (
              <button style={waBtnPrimary} onClick={sendAll}
                disabled={sending || allSent || sendableCount === 0 || !whatsappConfigured}
                title={whatsappConfigured ? undefined : t("channel.whatsappRequired")}>
                <Icon name="whatsapp" size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />
                {sending ? t("taxes.sending") : t("channel.sendWhatsappN", { n: sendableCount })}
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

const overlay: React.CSSProperties = { position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex", alignItems: "flex-start", justifyContent: "center", padding: "4vh 16px", zIndex: 60 };
const modal: React.CSSProperties = { background: "var(--surface)", borderRadius: 14, width: "min(620px, 96vw)", maxHeight: "88vh", display: "flex", flexDirection: "column", overflow: "hidden", boxShadow: "var(--shadow-modal)" };
const header: React.CSSProperties = { display: "flex", justifyContent: "space-between", alignItems: "flex-start", background: "var(--chrome-bg)", padding: "12px 16px" };
const closeBtn: React.CSSProperties = { background: "none", border: "none", color: "var(--chrome-text)", cursor: "pointer" };
const card: React.CSSProperties = { border: "1px solid var(--border)", borderRadius: 12, background: "var(--surface)", marginBottom: 12, overflow: "hidden" };
const cardHead: React.CSSProperties = { display: "flex", justifyContent: "space-between", alignItems: "center", padding: "10px 12px", borderBottom: "1px solid var(--hair)" };
const waIcon: React.CSSProperties = { width: 26, height: 26, borderRadius: 7, background: "#e7f8ef", display: "grid", placeItems: "center" };
const input: React.CSSProperties = { width: "100%", boxSizing: "border-box", padding: "7px 9px", border: "1px solid var(--border)", borderRadius: 8, fontSize: 13 };
const fileSet: React.CSSProperties = { border: "none", margin: "8px 0 0", padding: 0 };
const fileLegend: React.CSSProperties = { fontSize: 11, fontWeight: 600, color: "var(--text-muted)", textTransform: "uppercase", letterSpacing: "0.04em", marginBottom: 4 };
const fileRow: React.CSSProperties = { display: "flex", alignItems: "center", gap: 6, fontSize: 12.5, padding: "2px 0", cursor: "pointer" };
const footer: React.CSSProperties = { display: "flex", justifyContent: "space-between", alignItems: "center", padding: "12px 16px", borderTop: "1px solid var(--border)" };
const waBtnPrimary: React.CSSProperties = { background: "#128C7E", color: "#fff", border: "1px solid #0e6f64" };
