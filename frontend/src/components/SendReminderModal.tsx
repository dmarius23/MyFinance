import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { remindersApi } from "../api/documents";
import { emailApi } from "../api/email";
import { ApiError } from "../lib/apiClient";
import { useEmailConfigured } from "../lib/useEmailConfigured";
import { MissingInfoWarning } from "./MissingInfoWarning";

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)",
  display: "grid", placeItems: "center", zIndex: 60,
};

export interface ReminderTarget {
  id: string;
  name: string;
  hasBankStatement: boolean;
  hasInvoiceOrReceipt: boolean;
}

interface Draft { recipient: string; body: string; loading: boolean; sent: boolean; error?: string }

/**
 * Missing-documents reminder. The body is tailored per company by the server: it picks the
 * appropriate reminder state (nothing received / docs missing / docs only) and renders this
 * tenant's wording from the template. Each send is recorded (MOD-04/09), so the Statements list
 * shows "last sent" and the notification log keeps history.
 */
export function SendReminderModal({ companies, period, onClose }:
  { companies: ReminderTarget[]; period: string; onClose: () => void }) {
  const { t } = useTranslation();
  const emailConfigured = useEmailConfigured();
  const qc = useQueryClient();
  const month = period.slice(0, 7);
  const [drafts, setDrafts] = useState<Record<string, Draft>>(
    () => Object.fromEntries(companies.map((c) => [c.id, { recipient: "", body: "", loading: true, sent: false }])),
  );
  const [sending, setSending] = useState(false);

  // Prefill each company's representative as recipient.
  useEffect(() => {
    let cancelled = false;
    Promise.all(companies.map((c) => emailApi.envelope(c.id).then((e) => ({ id: c.id, e })).catch(() => ({ id: c.id, e: null }))))
      .then((results) => {
        if (cancelled) return;
        setDrafts((d) => {
          const next = { ...d };
          for (const r of results) if (r.e?.recipient) next[r.id] = { ...next[r.id], recipient: r.e.recipient };
          return next;
        });
      });
    return () => { cancelled = true; };
  }, [companies]);

  // The server decides which of the three reminder states applies and renders this tenant's wording,
  // so the email and the WhatsApp message always read identically. Previously the browser fetched every
  // company's transactions just to work this out itself.
  useEffect(() => {
    let cancelled = false;
    Promise.all(companies.map((c) =>
      remindersApi.body(c.id, period).then((r) => ({ id: c.id, body: r.body }))
        .catch(() => ({ id: c.id, body: "" })),
    )).then((results) => {
      if (cancelled) return;
      setDrafts((d) => {
        const next = { ...d };
        for (const r of results) {
          const cur = next[r.id];
          if (!cur || !cur.loading) continue; // never clobber an edit in progress
          next[r.id] = { ...cur, body: r.body, loading: false };
        }
        return next;
      });
    });
    return () => { cancelled = true; };
  }, [companies, period]);

  const patch = (id: string, p: Partial<Draft>) =>
    setDrafts((d) => ({ ...d, [id]: { ...d[id], ...p } }));

  const sendAll = async () => {
    setSending(true);
    for (const c of companies) {
      const d = drafts[c.id];
      if (!d || d.sent || d.loading || !d.body.trim()) continue;
      try {
        await remindersApi.send(c.id, { period, recipient: d.recipient, body: d.body });
        patch(c.id, { sent: true, error: undefined });
        void qc.invalidateQueries({ queryKey: ["doc-reminder-history", c.id, period] });
      } catch (e) {
        patch(c.id, { error: e instanceof ApiError ? e.message : "Send failed" });
      }
    }
    setSending(false);
    void qc.invalidateQueries({ queryKey: ["doc-reminders", period] });
  };

  const allSent = companies.every((c) => drafts[c.id]?.sent);

  return (
    <div style={overlay} onClick={onClose}>
      <div className="card" style={{ width: 660, maxWidth: "94vw", maxHeight: "88vh", overflow: "auto" }}
        onClick={(e) => e.stopPropagation()}>
        {allSent ? (
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
              {companies.map((c) => {
                const d = drafts[c.id];
                return (
                  <div key={c.id} style={{ border: "1px solid var(--border)", borderRadius: 10, overflow: "hidden" }}>
                    <div style={{ background: "#fafbfc", padding: "8px 12px", borderBottom: "1px solid var(--border)", fontSize: 13, display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                      <b>{c.name}</b>
                      {d?.sent && <span className="pill ok round">✓</span>}
                      {d?.error && <span className="pill danger round" title={d.error}>{t("email.failed")}</span>}
                    </div>
                    <div style={{ padding: 12 }}>
                      {d?.loading
                        ? <div style={{ fontSize: 13, color: "var(--text-muted)" }}>{t("common.loading")}</div>
                        : (
                          <>
                            <MissingInfoWarning problems={(d?.recipient ?? "").trim() ? [] : [t("warn.noEmail")]} />
                            <input
                              placeholder={t("taxes.recipient")} value={d?.recipient ?? ""} disabled={d?.sent}
                              onChange={(e) => patch(c.id, { recipient: e.target.value })}
                              style={input} />
                            <textarea
                              value={d?.body ?? ""} disabled={d?.sent}
                              onChange={(e) => patch(c.id, { body: e.target.value })}
                              style={{ ...input, minHeight: 150, marginTop: 8, fontFamily: "inherit", resize: "vertical" }} />
                          </>
                        )}
                    </div>
                  </div>
                );
              })}
            </div>
            <div style={{ display: "flex", justifyContent: "flex-end", gap: 8, marginTop: 14 }}>
              <button onClick={onClose}>{t("email.cancel")}</button>
              <button className="primary" onClick={sendAll} disabled={sending || allSent || !emailConfigured}
                title={emailConfigured ? undefined : t("email.smtpRequired")}>
                ✉ {sending ? t("taxes.sending") : t("email.sendNow")}
              </button>
            </div>
            <div style={{ color: "var(--text-muted)", fontSize: 11, marginTop: 8 }}>{t("taxes.eachLogged")}</div>
          </>
        )}
      </div>
    </div>
  );
}

const input: React.CSSProperties = {
  width: "100%", boxSizing: "border-box", padding: "7px 9px",
  border: "1px solid var(--border)", borderRadius: 8, fontSize: 13,
};
