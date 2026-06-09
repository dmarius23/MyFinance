import { useState } from "react";
import { useTranslation } from "react-i18next";

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(15,23,42,0.4)",
  display: "grid", placeItems: "center", zIndex: 60,
};

/**
 * Missing-documents reminder preview. Email delivery (MOD-07/09) isn't wired yet, so this mirrors
 * the prototype: it shows the predefined template per recipient and a confirm action that marks the
 * reminder as sent (preview only). Once the notifications module lands, the confirm handler calls
 * the real endpoint instead of flipping local state.
 */
export function SendReminderModal({ companies, period, onClose }:
  { companies: { id: string; name: string }[]; period: string; onClose: () => void }) {
  const { t } = useTranslation();
  const [sent, setSent] = useState(false);
  const month = period.slice(0, 7);
  const subject = t("email.subject", { month });
  const body = t("email.body");

  return (
    <div style={overlay} onClick={onClose}>
      <div className="card" style={{ width: 640, maxWidth: "94vw", maxHeight: "88vh", overflow: "auto" }}
        onClick={(e) => e.stopPropagation()}>
        {sent ? (
          <div style={{ textAlign: "center", padding: "8px 4px" }}>
            <div style={{ fontSize: 40, color: "#16a34a" }}>✓</div>
            <h2 style={{ margin: "6px 0" }}>{t("email.sentTitle")}</h2>
            <p style={{ color: "var(--text-muted)", fontSize: 13.5 }}>{t("email.sentBody", { n: companies.length })}</p>
            <button className="primary" style={{ marginTop: 14 }} onClick={onClose}>OK</button>
          </div>
        ) : (
          <>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <h2 style={{ margin: 0 }}>{t("email.reminderTitle")} ({companies.length})</h2>
              <button onClick={onClose}>✕</button>
            </div>
            <p style={{ color: "var(--text-muted)", fontSize: 13, margin: "8px 0 12px" }}>
              {t("email.previewIntro", { n: companies.length, month })}
            </p>
            <div style={{ display: "grid", gap: 10 }}>
              {companies.map((c) => (
                <div key={c.id} style={{ border: "1px solid var(--border)", borderRadius: 10, overflow: "hidden" }}>
                  <div style={{ background: "#fafbfc", padding: "8px 12px", borderBottom: "1px solid var(--border)", fontSize: 13 }}>
                    <b>{c.name}</b>
                  </div>
                  <div style={{ padding: 12, fontSize: 13, whiteSpace: "pre-line", lineHeight: 1.6 }}>
                    <b>{t("email.subjectLabel")}:</b> {subject}{"\n"}{body}
                  </div>
                </div>
              ))}
            </div>
            <div style={{ display: "flex", justifyContent: "flex-end", gap: 8, marginTop: 14 }}>
              <button onClick={onClose}>{t("email.cancel")}</button>
              <button className="primary" onClick={() => setSent(true)}>✉ {t("email.sendNow")}</button>
            </div>
            <div style={{ color: "var(--text-muted)", fontSize: 11, marginTop: 8 }}>{t("email.previewNote")}</div>
          </>
        )}
      </div>
    </div>
  );
}
