import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { bankApi, invoicesApi } from "../api/bank";
import { LinkTransactionModal } from "./LinkTransactionModal";

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)",
  display: "grid", placeItems: "center", zIndex: 65,
};
const fmt = (n: number) => n.toLocaleString("ro-RO", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const statusColors: Record<string, React.CSSProperties> = {
  PAID: { background: "#dcfce7", color: "#166534" },
  PARTIAL: { background: "#fef3c7", color: "#92400e" },
  UNPAID: { background: "#fee2e2", color: "#991b1b" },
};

/** Invoice-centric view: the invoice, its applied payments, remaining balance, and add/remove. */
export function InvoicePaymentsModal({ companyId, period, documentId, onClose }:
  { companyId: string; period: string; documentId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [adding, setAdding] = useState(false);

  const q = useQuery({
    queryKey: ["invoice-payments", companyId, documentId],
    queryFn: () => invoicesApi.paymentsByDocument(companyId, documentId),
  });
  const inv = q.data;

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ["invoice-payments", companyId, documentId] });
    void qc.invalidateQueries({ queryKey: ["bank-txns", companyId, period] });
    void qc.invalidateQueries({ queryKey: ["open-invoices", companyId, period] });
    void qc.invalidateQueries({ queryKey: ["open-txns", companyId, period] });
    void qc.invalidateQueries({ queryKey: ["recon-summary", period] });
    void qc.invalidateQueries({ queryKey: ["doc-status", companyId, period] });
  };

  const add = useMutation({
    mutationFn: (txnId: string) => bankApi.match(companyId, txnId, inv!.invoiceId),
    onSuccess: () => { invalidate(); setAdding(false); },
    onError: (e: unknown) => window.alert(`${t("recon.linkFailed")}: ${e instanceof Error ? e.message : String(e)}`),
  });
  const remove = useMutation({
    mutationFn: (txnId: string) => bankApi.unmatch(companyId, txnId, inv!.invoiceId),
    onSuccess: invalidate,
  });

  return (
    <div style={overlay} onClick={onClose}>
      <div className="card" style={{ width: 620, maxWidth: "94vw", maxHeight: "86vh", display: "flex", flexDirection: "column" }}
        onClick={(e) => e.stopPropagation()}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h2 style={{ margin: 0, fontSize: 17 }}>{t("recon.payments")}</h2>
          <button onClick={onClose}>✕</button>
        </div>

        {q.isLoading && <div style={{ color: "var(--text-muted)", padding: 12 }}>{t("common.loading")}</div>}
        {q.isError && <div style={{ color: "#991b1b", padding: 12 }}>{t("recon.noInvoices")}</div>}

        {inv && (
          <>
            {/* Invoice summary */}
            <div style={{ background: "#f8fafc", border: "1px solid var(--border)", borderRadius: 10, padding: 12, margin: "10px 0" }}>
              <div style={{ fontWeight: 600, overflowWrap: "anywhere" }}>{inv.filename ?? inv.supplierName ?? "factura"}</div>
              {inv.supplierName && <div style={{ color: "var(--text-muted)", fontSize: 12.5 }}>🏢 {inv.supplierName}</div>}
              <div style={{ color: "var(--text-muted)", fontSize: 12.5, marginTop: 2 }}>📅 {inv.invoiceDate ?? "—"}</div>
              <div style={{ display: "flex", gap: 16, marginTop: 8, fontSize: 13, flexWrap: "wrap", alignItems: "center" }}>
                <span>{t("recon.total")}: <b style={{ fontVariantNumeric: "tabular-nums" }}>{inv.totalAmount != null ? fmt(inv.totalAmount) : "—"}</b></span>
                <span>{t("recon.paid")}: <b style={{ fontVariantNumeric: "tabular-nums" }}>{fmt(inv.paidAmount)}</b></span>
                <span>{t("recon.remaining")}: <b style={{ fontVariantNumeric: "tabular-nums" }}>{inv.remaining != null ? fmt(inv.remaining) : "—"}</b></span>
                <span style={{ ...statusColors[inv.status], borderRadius: 999, padding: "2px 10px", fontSize: 12 }}>
                  {t(`recon.status.${inv.status}`)}
                </span>
              </div>
            </div>

            {/* Payments applied */}
            <div style={{ overflowY: "auto", flex: 1 }}>
              {inv.payments.length === 0 && (
                <div style={{ color: "var(--text-muted)", fontSize: 13, padding: "6px 2px" }}>{t("recon.noPayments")}</div>
              )}
              {inv.payments.map((p) => (
                <div key={p.txnId} style={{ display: "flex", alignItems: "center", gap: 10, padding: "8px 4px", borderBottom: "1px solid var(--border)" }}>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontWeight: 600 }}>{p.partnerName ?? "—"}</div>
                    <div style={{ color: "var(--text-muted)", fontSize: 12 }}>📅 {p.txnDate}</div>
                  </div>
                  <div style={{ textAlign: "right", whiteSpace: "nowrap", fontVariantNumeric: "tabular-nums" }}>
                    <b>{fmt(p.allocatedAmount)} RON</b>
                    {Math.abs(p.allocatedAmount - Math.abs(p.amount)) > 0.005 && (
                      <div style={{ fontSize: 11, color: "var(--text-muted)" }}>{t("recon.of", { total: fmt(Math.abs(p.amount)) })}</div>
                    )}
                  </div>
                  <button onClick={() => remove.mutate(p.txnId)} disabled={remove.isPending}
                    title={t("recon.unlink")} style={{ border: "none", background: "none", color: "#991b1b", cursor: "pointer" }}>✕</button>
                </div>
              ))}
            </div>

            <div style={{ display: "flex", justifyContent: "flex-end", marginTop: 12 }}>
              <button className="primary"
                disabled={inv.remaining != null && inv.remaining <= 0.01}
                onClick={() => setAdding(true)}>
                + {t("recon.addPayment")}
              </button>
            </div>
          </>
        )}
      </div>

      {adding && inv && (
        <LinkTransactionModal
          companyId={companyId}
          period={period}
          invoiceRemaining={inv.remaining}
          pending={add.isPending}
          onPick={(txnId) => add.mutate(txnId)}
          onClose={() => setAdding(false)}
        />
      )}
    </div>
  );
}
