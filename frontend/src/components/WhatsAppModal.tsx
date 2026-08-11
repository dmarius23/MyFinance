import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { whatsappApi, type WhatsAppKind } from "../api/whatsapp";
import { ApiError } from "../lib/apiClient";
import { Icon } from "./Icon";

const dt = (iso: string) => new Date(iso).toLocaleDateString("ro-RO", { day: "numeric", month: "short" });
const tm = (iso: string) => new Date(iso).toLocaleTimeString("ro-RO", { hour: "2-digit", minute: "2-digit" });

/**
 * Compose + history for the WhatsApp channel (Phase 1). Pre-fills the representative's phone, lets staff
 * edit the recipient + message, sends it, and shows the send timeline. Delivery is the backend stub for
 * now; a real provider (Twilio) plugs in behind the port later.
 */
export function WhatsAppModal({ companyId, companyName, kind, period, loadBody, onClose }:
  { companyId: string; companyName: string; kind: WhatsAppKind; period: string;
    loadBody?: () => Promise<string>; onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [phone, setPhone] = useState<string | null>(null);
  const [body, setBody] = useState("");
  const [bodyEdited, setBodyEdited] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const rk = ["whatsapp", companyId, kind, period];
  useQuery({
    queryKey: ["whatsapp-recipient", companyId],
    queryFn: async () => {
      const r = await whatsappApi.recipient(companyId);
      setPhone((p) => p ?? r.phone ?? "");
      return r;
    },
  });
  // Pre-fill the message with the same body the email uses (module- and file-specific). Only until edited.
  const draft = useQuery({
    queryKey: ["whatsapp-body", companyId, kind, period],
    queryFn: loadBody!,
    enabled: !!loadBody,
  });
  useEffect(() => {
    if (!bodyEdited && draft.data != null) setBody(draft.data);
  }, [draft.data, bodyEdited]);
  const history = useQuery({ queryKey: rk, queryFn: () => whatsappApi.history(companyId, kind, period) });

  const send = useMutation({
    mutationFn: () => whatsappApi.send(companyId, { kind, period, recipient: phone ?? undefined, body }),
    onSuccess: () => { setBody(""); setBodyEdited(false); setError(null); void qc.invalidateQueries({ queryKey: rk }); },
    onError: (e) => setError(e instanceof ApiError ? e.message : t("channel.sendFailed")),
  });

  const data = history.data ?? [];
  return (
    <div style={overlay} onClick={onClose}>
      <div style={modal} onClick={(e) => e.stopPropagation()}>
        <div style={header}>
          <div>
            <div style={{ color: "var(--chrome-muted)", fontSize: 11 }}>WhatsApp</div>
            <div style={{ color: "#f3f8f7", fontSize: 17, fontWeight: 700 }}>{companyName}</div>
          </div>
          <button onClick={onClose} style={closeBtn}><Icon name="x" size={16} /></button>
        </div>

        <div style={{ padding: 16, overflowY: "auto" }}>
          <label style={fieldLabel}>{t("channel.phone")}</label>
          <input type="tel" value={phone ?? ""} onChange={(e) => setPhone(e.target.value)}
            placeholder="+40…" style={input} />
          <label style={{ ...fieldLabel, marginTop: 10 }}>{t("channel.message")}</label>
          <textarea value={body} onChange={(e) => { setBody(e.target.value); setBodyEdited(true); }} rows={6}
            placeholder={loadBody && draft.isLoading ? t("common.loading") : undefined}
            style={{ ...input, resize: "vertical", fontFamily: "inherit" }} />
          {error && <div style={{ color: "#dc2626", fontSize: 12, marginTop: 6 }}>{error}</div>}
          <div style={{ marginTop: 10 }}>
            <button className="primary" disabled={send.isPending || !body.trim() || !(phone ?? "").trim()}
              onClick={() => send.mutate()}>
              <Icon name="whatsapp" size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />{t("channel.sendWhatsapp")}
            </button>
          </div>

          <div style={{ ...sectionLabel, marginTop: 18 }}>{t("channel.history")}</div>
          {history.isLoading && <p>{t("common.loading")}</p>}
          {!history.isLoading && data.length === 0 && (
            <div style={{ color: "var(--text-muted)", fontSize: 12.5, padding: "10px 0" }}>{t("channel.noneSent")}</div>
          )}
          {data.map((m) => (
            <div key={m.id} style={{ display: "flex", gap: 10, padding: "11px 0", borderTop: "1px solid var(--hair)" }}>
              <div style={waIcon}><Icon name="whatsapp" size={14} style={{ color: "#128C7E" }} /></div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 12.5, whiteSpace: "pre-wrap", wordBreak: "break-word" }}>{m.body}</div>
                <div className="mono" style={{ fontSize: 11, color: "var(--text-muted)" }}>{t("channel.to")} {m.recipient ?? "—"}</div>
              </div>
              <div style={{ textAlign: "right" }}>
                <div className="mono" style={{ fontSize: 12, fontWeight: 600 }}>{dt(m.sentAt)}</div>
                <div className="mono" style={{ fontSize: 11, color: "var(--text-muted)" }}>{tm(m.sentAt)}</div>
                <span className={`pill round ${m.status === "SENT" ? "ok" : m.status === "QUEUED" ? "info" : "danger"}`}
                  style={{ marginTop: 3, display: "inline-block", fontSize: 9.5 }}>{m.status}</span>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

const overlay: React.CSSProperties = { position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex", alignItems: "flex-start", justifyContent: "center", padding: "5vh 16px", zIndex: 55 };
const modal: React.CSSProperties = { background: "var(--surface)", borderRadius: 14, width: "min(560px, 95vw)", maxHeight: "86vh", display: "flex", flexDirection: "column", overflow: "hidden", boxShadow: "var(--shadow-modal)" };
const header: React.CSSProperties = { display: "flex", justifyContent: "space-between", alignItems: "flex-start", background: "var(--chrome-bg)", padding: "12px 16px" };
const closeBtn: React.CSSProperties = { background: "none", border: "none", color: "var(--chrome-text)", cursor: "pointer" };
const waIcon: React.CSSProperties = { width: 30, height: 30, flex: "none", borderRadius: 8, background: "#e7f8ef", display: "grid", placeItems: "center" };
const input: React.CSSProperties = { width: "100%", boxSizing: "border-box", padding: "7px 9px", border: "1px solid var(--border)", borderRadius: 8, fontSize: 13, background: "var(--surface)" };
const fieldLabel: React.CSSProperties = { display: "block", fontSize: 11, fontWeight: 600, color: "var(--text-muted)", textTransform: "uppercase", letterSpacing: "0.04em", marginBottom: 4 };
const sectionLabel: React.CSSProperties = { fontSize: 10, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--text-muted)", fontWeight: 700, marginBottom: 4 };
