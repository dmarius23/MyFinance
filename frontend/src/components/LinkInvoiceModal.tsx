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

  const monthIdx = (ym: string) => { const [y, m] = ym.split("-").map(Number); return y * 12 + (m - 1); };
  const ymOf = (idx: number) => `${Math.floor(idx / 12)}-${String((idx % 12) + 1).padStart(2, "0")}`;
  const curIdx = monthIdx(curMonth);

  // Fixed buckets — current month, previous month, the month before, then "older than 3 months".
  // A bucket's header shows only if there are invoices in that period OR older (current always shows).
  const groups = useMemo(() => {
    const needle = q.trim().toLowerCase();
    const filtered = (open.data ?? []).filter((inv) => {
      if (!needle) return true;
      return [inv.filename, inv.supplierName, inv.remaining?.toString(), inv.totalAmount?.toString(), inv.invoiceDate]
        .filter(Boolean).some((f) => String(f).toLowerCase().includes(needle));
    });
    const ageOf = (inv: OpenInvoice) => Math.max(0, curIdx - monthIdx(inv.periodMonth.slice(0, 7)));
    const buckets: OpenInvoice[][] = [[], [], [], []]; // 0=current, 1=prev, 2=before, 3=older(>=3)
    let maxAge = 0;
    for (const inv of filtered) {
      const a = ageOf(inv);
      maxAge = Math.max(maxAge, a);
      buckets[Math.min(a, 3)].push(inv);
    }
    const sortFn = (a: OpenInvoice, b: OpenInvoice) =>
      (amtMatchOf(a) ? 0 : 1) - (amtMatchOf(b) ? 0 : 1)
      || Math.abs((a.invoiceDate ?? "").localeCompare(tx.txnDate)) - Math.abs((b.invoiceDate ?? "").localeCompare(tx.txnDate));
    buckets.forEach((arr) => arr.sort(sortFn));
    return [
      { key: "cur", label: t("recon.thisMonth"), items: buckets[0], show: true },
      { key: "prev", label: ymOf(curIdx - 1), items: buckets[1], show: maxAge >= 1 },
      { key: "before", label: ymOf(curIdx - 2), items: buckets[2], show: maxAge >= 2 },
      { key: "older", label: t("recon.olderThan3"), items: buckets[3], show: maxAge >= 3 },
    ].filter((g) => g.show);
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
          {!open.isLoading && q.trim() !== "" && total === 0 && (
            <div style={{ color: "var(--text-muted)", padding: 10, fontSize: 13 }}>{t("recon.noMatchSearch")}</div>
          )}
          {!open.isLoading && groups.map((g) => (
            <div key={g.key}>
              <div style={{
                position: "sticky", top: 0, background: "#fff", padding: "6px 4px 4px", fontSize: 11,
                fontWeight: 700, color: "var(--text-muted)", textTransform: "uppercase", borderBottom: "1px solid var(--border)",
              }}>
                {g.label}
              </div>
              {g.items.length === 0 && (
                <div style={{ color: "var(--text-muted)", fontSize: 12, padding: "6px 8px" }}>—</div>
              )}
              {g.items.map((inv) => {
                const amtMatch = amtMatchOf(inv);
                const partlyPaid = inv.paidAmount > 0.005;
                // Picker only shows open invoices → orange = partially paid, red = not paid at all.
                const pay = partlyPaid ? { bg: "#fef3c7", bd: "#d97706" } : { bg: "#fee2e2", bd: "#dc2626" };
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
                      <div style={{ display: "flex", alignItems: "center", gap: 6, flexWrap: "wrap" }}>
                        <span style={{ fontWeight: 600, overflowWrap: "anywhere" }}>{inv.filename ?? inv.supplierName ?? "factura"}</span>
                        {inv.duplicate && (
                          <span title={t("doc.warn.duplicate")} style={{ background: "#fee2e2", color: "#b91c1c", border: "1px solid #fecaca", borderRadius: 999, padding: "0 7px", fontSize: 10, fontWeight: 600, textTransform: "uppercase" }}>
                            {t("doc.duplicateChip")}
                          </span>
                        )}
                      </div>
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
                    {/* Payment status (same style as the files list): orange = partial, red = unpaid. */}
                    <span title={t(`recon.status.${partlyPaid ? "PARTIAL" : "UNPAID"}`)}
                      style={{ background: pay.bg, border: `1px solid ${pay.bd}`, color: pay.bd, borderRadius: 7, fontSize: 13, lineHeight: 1, padding: "3px 6px", width: 30, textAlign: "center", flexShrink: 0 }}>
                      💰
                    </span>
                    {/* Preview the document before associating — doesn't trigger the row's link action. */}
                    <button
                      onClick={(e) => { e.stopPropagation(); setPreview({ documentId: inv.documentId, filename: inv.filename }); }}
                      title={t("recon.viewDoc")}
                      style={{ border: "1px solid var(--border)", background: "#fff", borderRadius: 7, cursor: "pointer", fontSize: 15, padding: "2px 7px", flexShrink: 0 }}>
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
