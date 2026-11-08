import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { bankApi, type OpenTransaction } from "../api/bank";
import { formatMoney } from "../lib/money";

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)",
  display: "grid", placeItems: "center", zIndex: 70,
};

/**
 * Picks a bank transaction to apply as a payment to an invoice (invoice-centric flow). Lists the
 * company's open transactions (need a document, remaining > 0) across the rolling window, ranks the
 * one whose remaining matches the invoice's remaining first, and searches partner/amount.
 */
export function LinkTransactionModal({ companyId, period, invoiceRemaining, invoiceCurrency, pending, onPick, onClose }: {
  companyId: string;
  period: string;
  invoiceRemaining: number | null;
  /** Currency of the invoice being paid. Shown beside each transaction's remaining so a cross-currency link is visible. */
  invoiceCurrency: string;
  pending: boolean;
  onPick: (txnId: string) => void;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  const [q, setQ] = useState("");

  const open = useQuery({
    queryKey: ["open-txns", companyId, period],
    queryFn: () => bankApi.openTransactions(companyId, period),
  });

  const amtMatchOf = (tx: OpenTransaction) =>
    invoiceRemaining != null && Math.abs(tx.remaining - invoiceRemaining) < 0.01;

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase();
    const all = open.data ?? [];
    const filtered = all.filter((tx) => {
      if (!needle) return true;
      return [tx.partnerName, tx.partnerIban, tx.remaining.toString(), Math.abs(tx.amount).toString(), tx.txnDate]
        .filter(Boolean).some((f) => String(f).toLowerCase().includes(needle));
    });
    return filtered.sort((a, b) => (amtMatchOf(a) ? 0 : 1) - (amtMatchOf(b) ? 0 : 1)
      || b.txnDate.localeCompare(a.txnDate));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open.data, q, invoiceRemaining]);

  return (
    <div style={overlay} onClick={onClose}>
      <div className="card" style={{ width: 600, maxWidth: "94vw", maxHeight: "80vh", display: "flex", flexDirection: "column" }}
        onClick={(e) => e.stopPropagation()}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h2 style={{ margin: 0, fontSize: 17 }}>{t("recon.linkTxnTitle")}</h2>
          <button onClick={onClose}>✕</button>
        </div>
        {invoiceRemaining != null && (
          <div style={{ fontSize: 12, color: "var(--text-muted)", marginTop: 4 }}>
            {t("recon.remaining")}: <b style={{ fontVariantNumeric: "tabular-nums" }}>{formatMoney(invoiceRemaining, invoiceCurrency, i18n.language)}</b>
          </div>
        )}
        <input
          autoFocus
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder={t("recon.searchTxn")}
          style={{ width: "100%", padding: "8px 10px", borderRadius: 8, border: "1px solid var(--border)", fontSize: 13, boxSizing: "border-box", marginTop: 10 }}
        />
        <div style={{ overflowY: "auto", marginTop: 8, flex: 1 }}>
          {open.isLoading && <div style={{ color: "var(--text-muted)", padding: 10, fontSize: 13 }}>{t("common.loading")}</div>}
          {!open.isLoading && rows.length === 0 && (
            <div style={{ color: "var(--text-muted)", padding: 10, fontSize: 13 }}>{t("recon.noOpenTxns")}</div>
          )}
          {rows.map((tx) => {
            const amtMatch = amtMatchOf(tx);
            const partlyAllocated = tx.allocatedAmount > 0.005;
            return (
              <div key={tx.id}
                onClick={() => !pending && onPick(tx.id)}
                style={{
                  cursor: pending ? "default" : "pointer", padding: "9px 10px", borderRadius: 8,
                  borderBottom: "1px solid var(--border)", opacity: pending ? 0.6 : 1,
                  display: "flex", alignItems: "center", gap: 12,
                }}
                onMouseEnter={(e) => (e.currentTarget.style.background = "var(--primary-light, #eef2ff)")}
                onMouseLeave={(e) => (e.currentTarget.style.background = "transparent")}
              >
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 600, overflowWrap: "anywhere" }}>{tx.partnerName ?? "—"}</div>
                  <div style={{ color: "var(--text-muted)", fontSize: 12 }}>📅 {tx.txnDate}</div>
                </div>
                <div style={{ textAlign: "right", whiteSpace: "nowrap" }}>
                  <div style={{ fontVariantNumeric: "tabular-nums", fontWeight: 600, color: amtMatch ? "#166534" : "inherit" }}>
                    {formatMoney(tx.remaining, tx.currency, i18n.language)}{amtMatch ? " ✓" : ""}
                  </div>
                  <div style={{ fontSize: 11, color: "var(--text-muted)" }}>
                    {partlyAllocated ? `${t("recon.remaining")} ${t("recon.of", { total: formatMoney(Math.abs(tx.amount), tx.currency, i18n.language) })}` : t("recon.remaining")}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
