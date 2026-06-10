import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import type { BankTransaction, Invoice } from "../api/bank";

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(15,23,42,0.4)",
  display: "grid", placeItems: "center", zIndex: 60,
};
const fmt = (n: number) => n.toLocaleString("ro-RO", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/**
 * Picks an invoice/receipt to link to a transaction. A dedicated wide popover (rather than an in-cell
 * dropdown) so the full list — filename, issuing party, date, amount, assignment status — is readable
 * and searchable even when long. Candidates are ranked: exact amount match, then unassigned, then
 * nearest issue date.
 */
export function LinkInvoiceModal({ tx, invoices, assignedTo, pending, onPick, onClose }: {
  tx: BankTransaction;
  invoices: Invoice[];
  assignedTo: Map<string, { txnDate: string; partnerName: string | null }[]>;
  pending: boolean;
  onPick: (invoiceId: string) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [q, setQ] = useState("");

  const amtMatchOf = (inv: Invoice) =>
    inv.totalAmount != null && Math.abs(Math.abs(inv.totalAmount) - Math.abs(tx.amount)) < 0.01;

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase();
    const filtered = invoices.filter((inv) => {
      if (!needle) return true;
      return [inv.filename, inv.supplierName, inv.totalAmount?.toString(), inv.invoiceDate]
        .filter(Boolean).some((f) => String(f).toLowerCase().includes(needle));
    });
    return filtered.sort((a, b) =>
      (amtMatchOf(a) ? 0 : 1) - (amtMatchOf(b) ? 0 : 1)
      || ((assignedTo.get(a.id)?.length ?? 0) > 0 ? 1 : 0) - ((assignedTo.get(b.id)?.length ?? 0) > 0 ? 1 : 0)
      || Math.abs((a.invoiceDate ?? "").localeCompare(tx.txnDate)) - Math.abs((b.invoiceDate ?? "").localeCompare(tx.txnDate)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [invoices, q, assignedTo, tx.amount, tx.txnDate]);

  return (
    <div style={overlay} onClick={onClose}>
      <div className="card" style={{ width: 600, maxWidth: "94vw", maxHeight: "82vh", display: "flex", flexDirection: "column" }}
        onClick={(e) => e.stopPropagation()}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h2 style={{ margin: 0, fontSize: 17 }}>{t("recon.linkTitle")}</h2>
          <button onClick={onClose}>✕</button>
        </div>

        {/* What we're linking against. */}
        <div style={{ background: "#f8fafc", border: "1px solid var(--border)", borderRadius: 10, padding: "8px 12px", margin: "10px 0", fontSize: 13 }}>
          <span style={{ color: "var(--text-muted)" }}>{t("recon.linkForTxn")} </span>
          <b>{tx.txnDate}</b> · {tx.partnerName ?? "—"} ·{" "}
          <span style={{ fontVariantNumeric: "tabular-nums" }}>{tx.amount < 0 ? "-" : "+"}{fmt(Math.abs(tx.amount))} RON</span>
        </div>

        <input
          autoFocus
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder={t("recon.search")}
          style={{ width: "100%", padding: "8px 10px", borderRadius: 8, border: "1px solid var(--border)", fontSize: 13, boxSizing: "border-box" }}
        />

        <div style={{ overflowY: "auto", marginTop: 8, flex: 1 }}>
          {rows.length === 0 && (
            <div style={{ color: "var(--text-muted)", padding: 10, fontSize: 13 }}>
              {invoices.length === 0 ? t("recon.noInvoices") : t("recon.noMatchSearch")}
            </div>
          )}
          {rows.map((inv) => {
            const assigned = assignedTo.get(inv.id) ?? [];
            const amtMatch = amtMatchOf(inv);
            return (
              <div key={inv.id}
                onClick={() => !pending && onPick(inv.id)}
                title={t("recon.pickInvoice")}
                style={{
                  cursor: pending ? "default" : "pointer", padding: "9px 10px", borderRadius: 8,
                  borderBottom: "1px solid var(--border)", opacity: pending ? 0.6 : 1,
                  display: "flex", alignItems: "center", gap: 12,
                }}
                onMouseEnter={(e) => (e.currentTarget.style.background = "var(--primary-light, #eef2ff)")}
                onMouseLeave={(e) => (e.currentTarget.style.background = "transparent")}
              >
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 600, overflowWrap: "anywhere" }}>{inv.filename ?? inv.supplierName ?? "factura"}</div>
                  {inv.supplierName && (
                    <div style={{ color: "var(--text-muted)", fontSize: 12, overflowWrap: "anywhere" }}>🏢 {inv.supplierName}</div>
                  )}
                  <div style={{ color: "var(--text-muted)", fontSize: 12, marginTop: 1 }}>📅 {inv.invoiceDate ?? "—"}</div>
                </div>
                <div style={{ textAlign: "right", whiteSpace: "nowrap" }}>
                  <div style={{ fontVariantNumeric: "tabular-nums", fontWeight: 600, color: amtMatch ? "#166534" : "inherit" }}>
                    {inv.totalAmount != null ? `${fmt(inv.totalAmount)} RON` : "—"}{amtMatch ? " ✓" : ""}
                  </div>
                  {assigned.length > 0
                    ? <span title={assigned.map((a) => `${a.txnDate} · ${a.partnerName ?? "—"}`).join("\n")}
                        style={{ display: "inline-block", marginTop: 4, background: "#fef3c7", color: "#92400e", borderRadius: 999, padding: "1px 8px", fontSize: 11 }}>
                        {t("recon.assignedTo", { n: assigned.length })}
                      </span>
                    : <span style={{ display: "inline-block", marginTop: 4, background: "#dcfce7", color: "#166534", borderRadius: 999, padding: "1px 8px", fontSize: 11 }}>
                        {t("recon.unassigned")}
                      </span>}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
