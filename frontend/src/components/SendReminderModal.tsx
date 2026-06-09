import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueries } from "@tanstack/react-query";
import { bankApi, type BankTransaction } from "../api/bank";

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(15,23,42,0.4)",
  display: "grid", placeItems: "center", zIndex: 60,
};

const fmt = (n: number) => n.toLocaleString("ro-RO", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export interface ReminderTarget {
  id: string;
  name: string;
  hasBankStatement: boolean;
  hasInvoiceOrReceipt: boolean;
}

/**
 * Missing-documents reminder preview. The body is tailored per company: if the bank statement is
 * already uploaded we ask only for the missing invoices/receipts and list the specific transactions
 * still lacking a document; otherwise we ask for the statement too.
 *
 * Email delivery (MOD-07/09) isn't wired yet, so confirming is preview-only (mirrors the prototype);
 * the confirm handler is the single place to call the real endpoint once it exists.
 */
export function SendReminderModal({ companies, period, onClose }:
  { companies: ReminderTarget[]; period: string; onClose: () => void }) {
  const { t } = useTranslation();
  const [sent, setSent] = useState(false);
  const month = period.slice(0, 7);

  // Fetch each company's transactions so we can suggest the ones missing a document.
  const txnQueries = useQueries({
    queries: companies.map((c) => ({
      queryKey: ["bank-txns", c.id, period],
      queryFn: () => bankApi.transactions(c.id, period),
      enabled: c.hasBankStatement, // no statement → nothing to suggest
    })),
  });

  const missingFor = (i: number): BankTransaction[] =>
    (txnQueries[i].data ?? []).filter((tx) => tx.requiresDocument && !tx.matched);

  const bodyFor = (c: ReminderTarget, missing: BankTransaction[]): string => {
    const lines = [t("email.greeting"), ""];
    if (!c.hasBankStatement) {
      lines.push(t("email.needStatementAndDocs", { month }));
    } else if (missing.length > 0) {
      lines.push(t("email.needDocsForTxns", { month }));
      for (const tx of missing) {
        lines.push(`• ${tx.txnDate} — ${tx.partnerName ?? "—"} — ${fmt(Math.abs(tx.amount))} RON`);
      }
    } else {
      lines.push(t("email.needDocs", { month }));
    }
    lines.push("", t("email.uploadPortal"), "", t("email.signoff"));
    return lines.join("\n");
  };

  return (
    <div style={overlay} onClick={onClose}>
      <div className="card" style={{ width: 660, maxWidth: "94vw", maxHeight: "88vh", overflow: "auto" }}
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
              {companies.map((c, i) => {
                const loading = c.hasBankStatement && txnQueries[i].isLoading;
                const missing = missingFor(i);
                return (
                  <div key={c.id} style={{ border: "1px solid var(--border)", borderRadius: 10, overflow: "hidden" }}>
                    <div style={{ background: "#fafbfc", padding: "8px 12px", borderBottom: "1px solid var(--border)", fontSize: 13 }}>
                      <b>{c.name}</b>
                    </div>
                    <div style={{ padding: 12, fontSize: 13, whiteSpace: "pre-line", lineHeight: 1.6 }}>
                      <b>{t("email.subjectLabel")}:</b> {t("email.subject", { month })}{"\n"}
                      {loading ? t("common.loading") : bodyFor(c, missing)}
                    </div>
                  </div>
                );
              })}
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
