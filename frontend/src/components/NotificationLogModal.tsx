import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { taxPaymentsApi, type EmailView } from "../api/taxes";
import { Icon } from "./Icon";

const dt = (iso: string) => new Date(iso).toLocaleDateString("ro-RO", { day: "numeric", month: "short" });
const tm = (iso: string) => new Date(iso).toLocaleTimeString("ro-RO", { hour: "2-digit", minute: "2-digit" });

/** Per-company email log (B skin): timeline of sends + a "send a new" bar. Triggered by the Last-sent pill. */
export function NotificationLogModal({ companyId, companyName, period, onClose, onCompose }:
  { companyId: string; companyName: string; period: string; onClose: () => void; onCompose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data = [], isLoading } = useQuery({
    queryKey: ["tax-emails", companyId, period],
    queryFn: () => taxPaymentsApi.emailHistory(companyId, period),
  });

  const resend = useMutation({
    mutationFn: (e: EmailView) => taxPaymentsApi.sendEmail(companyId, {
      period, declarationIds: e.declarationIds, recipient: e.recipient ?? "", body: e.body,
    }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["tax-emails", companyId, period] });
      void qc.invalidateQueries({ queryKey: ["tax-list", period] });
    },
  });

  const lastTo = data.find((e) => e.recipient)?.recipient;

  return (
    <div style={overlay} onClick={onClose}>
      <div style={modal} onClick={(e) => e.stopPropagation()}>
        <div style={header}>
          <div>
            <div style={{ color: "var(--chrome-muted)", fontSize: 11 }}>{t("taxes.emailsSent")}</div>
            <div style={{ color: "#f3f8f7", fontSize: 17, fontWeight: 700 }}>{companyName}</div>
            <div style={{ color: "var(--chrome-text)", fontSize: 12 }}>
              {data.length} {t("taxes.sent").toLowerCase()}{lastTo ? ` · ${t("taxes.to")} ${lastTo}` : ""}
            </div>
          </div>
          <button onClick={onClose} style={closeBtn}><Icon name="x" size={16} /></button>
        </div>

        <div style={{ padding: 16, overflowY: "auto" }}>
          {/* send-a-new bar */}
          <div style={{ fontSize: 10, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--text-muted)", fontWeight: 700, marginBottom: 6 }}>
            {t("taxes.sendNew")}
          </div>
          <div style={{ display: "flex", gap: 8, marginBottom: 16 }}>
            <button className="primary" onClick={() => { onClose(); onCompose(); }}>
              <Icon name="mail" size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />{t("taxes.sendNow")}
            </button>
            <button disabled style={{ display: "flex", alignItems: "center", gap: 6, borderStyle: "dashed" }}>
              WhatsApp <span className="pill purple" style={{ fontSize: 9 }}>SOON</span>
            </button>
          </div>

          <div style={{ fontSize: 10, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--text-muted)", fontWeight: 700, marginBottom: 4 }}>
            {t("taxes.history")}
          </div>
          {isLoading && <p>{t("common.loading")}</p>}
          {!isLoading && data.length === 0 && <div style={{ color: "var(--text-muted)", fontSize: 12.5, padding: "10px 0" }}>{t("taxes.noEmails")}</div>}
          {data.map((e) => (
            <div key={e.id} style={{ display: "flex", gap: 10, padding: "11px 0", borderTop: "1px solid var(--hair)" }}>
              <div style={mailIcon}><Icon name="mail" size={14} style={{ color: "var(--teal-chip-fg)" }} /></div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 12.5, fontWeight: 600 }}>{e.declarationIds.length} {t("taxes.decl")}</div>
                <div className="mono" style={{ fontSize: 11, color: "var(--text-muted)" }}>{t("taxes.to")} {e.recipient ?? "—"}</div>
                <button onClick={() => resend.mutate(e)} disabled={resend.isPending} style={linkBtn}>{t("taxes.resend")}</button>
              </div>
              <div style={{ textAlign: "right" }}>
                <div className="mono" style={{ fontSize: 12, fontWeight: 600 }}>{dt(e.sentAt)}</div>
                <div className="mono" style={{ fontSize: 11, color: "var(--text-muted)" }}>{tm(e.sentAt)}</div>
                <span className={`pill round ${e.status === "SENT" ? "ok" : "danger"}`} style={{ marginTop: 3, display: "inline-block", fontSize: 9.5 }}>{e.status}</span>
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
const mailIcon: React.CSSProperties = { width: 30, height: 30, flex: "none", borderRadius: 8, background: "var(--teal-chip-bg)", display: "grid", placeItems: "center" };
const linkBtn: React.CSSProperties = { background: "none", border: "none", color: "var(--primary-dark)", cursor: "pointer", padding: 0, fontSize: 12, marginTop: 3 };
