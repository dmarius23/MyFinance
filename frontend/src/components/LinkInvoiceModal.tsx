import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { invoicesApi, type BankTransaction, type OpenInvoice } from "../api/bank";
import { DocumentPreviewModal } from "./DocumentPreviewModal";

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(15,23,42,0.4)",
  display: "grid", placeItems: "center", zIndex: 60,
};
const fmt = (n: number) => n.toLocaleString("ro-RO", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/**
 * Picks an invoice/receipt to link to a transaction. Sources the company's OPEN invoices across a
 * rolling window (so an installment in the current month can settle an invoice uploaded months ago),
 * shows each one's remaining balance, and groups them by month (current first, then older). Each row
 * has a preview trigger so the user can inspect the document before associating it. Linking allocates
 * min(payment remaining, invoice remaining).
 */
export function LinkInvoiceModal({ companyId, period, tx, pending, onPick, onClose }: {
  companyId: string;
  period: string;
  tx: BankTransaction;
  pending: boolean;
  onPick: (invoiceId: string) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [q, setQ] = useState("");
  const [preview, setPreview] = useState<{ documentId: string; filename: string | null } | null>(null);

  const open = useQuery({
    queryKey: ["open-invoices", companyId, period],
    queryFn: () => invoicesApi.open(companyId, period),
  });

  // How much of this payment is still unallocated (what we can apply here).
  const txnRemaining = tx.remainingAmount ?? Math.abs(tx.amount);
  const curMonth = period.slice(0, 7);
  const amtMatchOf = (inv: OpenInvoice) =>
    inv.remaining != null && Math.abs(inv.remaining - txnRemaining) < 0.01;

  // Group by month: current month first, then older months descending; ranked within each group.
  const groups = useMemo(() => {
    const needle = q.trim().toLowerCase();
    const filtered = (open.data ?? []).filter((inv) => {
      if (!needle) return true;
      return [inv.filename, inv.supplierName, inv.remaining?.toString(), inv.totalAmount?.toString(), inv.invoiceDate]
        .filter(Boolean).some((f) => String(f).toLowerCase().includes(needle));
    });
    const byMonth = new Map<string, OpenInvoice[]>();
    for (const inv of filtered) {
      const m = inv.periodMonth.slice(0, 7);
      if (!byMonth.has(m)) byMonth.set(m, []);
      byMonth.get(m)!.push(inv);
    }
    for (const arr of byMonth.values()) {
      arr.sort((a, b) =>
        (amtMatchOf(a) ? 0 : 1) - (amtMatchOf(b) ? 0 : 1)
        || Math.abs((a.invoiceDate ?? "").localeCompare(tx.txnDate)) - Math.abs((b.invoiceDate ?? "").localeCompare(tx.txnDate)));
    }
    const months = [...byMonth.keys()].sort((a, b) =>
      a === curMonth ? -1 : b === curMonth ? 1 : b.localeCompare(a));
    return months.map((m) => ({ month: m, items: byMonth.get(m)! }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open.data, q, txnRemaining, tx.txnDate]);

  const total = groups.reduce((n, g) => n + g.items.length, 0);

  return (
    <div style={overlay} onClick={onClose}>
      <div className="card" style={{ width: 640, maxWidth: "94vw", maxHeight: "82vh", display: "flex", flexDirection: "column" }}
        onClick={(e) => e.stopPropagation()}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h2 style={{ margin: 0, fontSize: 17 }}>{t("recon.linkTitle")}</h2>
          <button onClick={onClose}>✕</button>
        </div>

        {/* What we're linking against + how much is left to allocate. */}
        <div style={{ background: "#f8fafc", border: "1px solid var(--border)", borderRadius: 10, padding: "8px 12px", margin: "10px 0", fontSize: 13 }}>
          <span style={{ color: "var(--text-muted)" }}>{t("recon.linkForTxn")} </span>
          <b>{tx.txnDate}</b> · {tx.partnerName ?? "—"} ·{" "}
          <span style={{ fontVariantNumeric: "tabular-nums" }}>{tx.amount < 0 ? "-" : "+"}{fmt(Math.abs(tx.amount))} RON</span>
          <span style={{ marginLeft: 8, color: "var(--text-muted)" }}>
            · {t("recon.remainingToAllocate")}: <b>{fmt(txnRemaining)} RON</b>
          </span>
        </div>

        <input
          autoFocus
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder={t("recon.search")}
          style={{ width: "100%", padding: "8px 10px", borderRadius: 8, border: "1px solid var(--border)", fontSize: 13, boxSizing: "border-box" }}
        />

        <div style={{ overflowY: "auto", marginTop: 8, flex: 1 }}>
          {open.isLoading && <div style={{ color: "var(--text-muted)", padding: 10, fontSize: 13 }}>{t("common.loading")}</div>}
          {!open.isLoading && total === 0 && (
            <div style={{ color: "var(--text-muted)", padding: 10, fontSize: 13 }}>
              {(open.data ?? []).length === 0 ? t("recon.noInvoices") : t("recon.noMatchSearch")}
            </div>
          )}
          {groups.map((g) => (
            <div key={g.month}>
              <div style={{
                position: "sticky", top: 0, background: "#fff", padding: "6px 4px 4px", fontSize: 11,
                fontWeight: 700, color: "var(--text-muted)", textTransform: "uppercase", borderBottom: "1px solid var(--border)",
              }}>
                {g.month === curMonth ? t("recon.thisMonth") : g.month}
              </div>
              {g.items.map((inv) => {
                const amtMatch = amtMatchOf(inv);
                const partlyPaid = inv.paidAmount > 0.005;
                return (
                  <div key={inv.id}
                    onClick={() => !pending && onPick(inv.id)}
                    title={t("recon.pickInvoice")}
                    style={{
                      cursor: pending ? "default" : "pointer", padding: "9px 10px", borderRadius: 8,
                      borderBottom: "1px solid var(--border)", opacity: pending ? 0.6 : 1,
                      display: "flex", alignItems: "center", gap: 10,
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
                        {inv.remaining != null ? `${fmt(inv.remaining)} RON` : "—"}{amtMatch ? " ✓" : ""}
                      </div>
                      <div style={{ fontSize: 11, color: "var(--text-muted)" }}>
                        {partlyPaid && inv.totalAmount != null
                          ? `${t("recon.remaining")} ${t("recon.of", { total: fmt(inv.totalAmount) })}`
                          : t("recon.remaining")}
                      </div>
                    </div>
                    {/* Preview the document before associating — doesn't trigger the row's link action. */}
                    <button
                      onClick={(e) => { e.stopPropagation(); setPreview({ documentId: inv.documentId, filename: inv.filename }); }}
                      title={t("recon.viewDoc")}
                      style={{ border: "1px solid var(--border)", background: "#fff", borderRadius: 7, cursor: "pointer", fontSize: 15, padding: "2px 7px" }}>
                      👁
                    </button>
                  </div>
                );
              })}
            </div>
          ))}
        </div>
      </div>

      {preview && (
        <DocumentPreviewModal companyId={companyId} documentId={preview.documentId}
          filename={preview.filename} onClose={() => setPreview(null)} />
      )}
    </div>
  );
}
