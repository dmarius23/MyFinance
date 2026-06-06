import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { bankApi } from "../api/bank";

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(15,23,42,0.4)",
  display: "grid", placeItems: "center", zIndex: 50,
};

const fmt = (n: number) =>
  n.toLocaleString("ro-RO", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export function ReconModal({ companyId, companyName, period, onClose }:
  { companyId: string; companyName: string; period: string; onClose: () => void }) {
  const { t } = useTranslation();
  const statements = useQuery({ queryKey: ["bank-stmts", companyId, period], queryFn: () => bankApi.statements(companyId, period) });
  const txns = useQuery({ queryKey: ["bank-txns", companyId, period], queryFn: () => bankApi.transactions(companyId, period) });

  const list = txns.data ?? [];

  return (
    <div style={overlay} onClick={onClose}>
      <div className="card" style={{ width: 920, maxWidth: "97vw", maxHeight: "92vh", overflow: "auto" }} onClick={(e) => e.stopPropagation()}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h2 style={{ margin: 0 }}>{t("recon.title")} — {companyName}</h2>
          <button onClick={onClose}>✕</button>
        </div>
        <div style={{ color: "var(--text-muted)", fontSize: 12.5, margin: "4px 0 12px" }}>
          {t("recon.parsed", { n: list.length })}
        </div>

        {(statements.data ?? []).map((s) => (
          <div key={s.id} style={{ display: "flex", gap: 10, alignItems: "center", fontSize: 12.5, padding: "6px 0", borderBottom: "1px solid var(--border)" }}>
            <b>{s.bankCode ?? "—"}</b>
            <span style={{ color: "var(--text-muted)" }}>{s.accountIban ?? ""}</span>
            <span style={{ color: "var(--text-muted)" }}>
              {s.openingBalance != null ? fmt(s.openingBalance) : "—"} → {s.closingBalance != null ? fmt(s.closingBalance) : "—"}
            </span>
            <span style={{ color: s.crossCheckOk ? "#166534" : "#991b1b" }}>
              {s.crossCheckOk ? "✓" : "⚠"} {s.status}
            </span>
          </div>
        ))}

        <table style={{ width: "100%", borderCollapse: "collapse", marginTop: 12 }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8 }}>{t("recon.date")}</th>
              <th style={{ padding: 8 }}>{t("recon.partner")}</th>
              <th style={{ padding: 8, textAlign: "right" }}>{t("recon.amount")}</th>
            </tr>
          </thead>
          <tbody>
            {list.map((tx) => (
              <tr key={tx.id} style={{ borderTop: "1px solid var(--border)" }}>
                <td style={{ padding: 8, whiteSpace: "nowrap" }}>{tx.txnDate}</td>
                <td style={{ padding: 8 }}>
                  <b>{tx.partnerName ?? "—"}</b>
                  <div style={{ color: "var(--text-muted)", fontSize: 12 }}>
                    {[tx.description, tx.partnerIban].filter(Boolean).join(" · ")}
                  </div>
                </td>
                <td style={{ padding: 8, textAlign: "right", fontVariantNumeric: "tabular-nums", color: tx.amount < 0 ? "inherit" : "#166534" }}>
                  {tx.amount < 0 ? "-" : "+"}{fmt(Math.abs(tx.amount))}
                </td>
              </tr>
            ))}
            {list.length === 0 && (
              <tr><td colSpan={3} style={{ padding: 8, color: "var(--text-muted)" }}>—</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
