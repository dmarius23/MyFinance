import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { bankApi } from "../api/bank";
import { LinkInvoiceModal } from "./LinkInvoiceModal";

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(15,23,42,0.4)",
  display: "grid", placeItems: "center", zIndex: 50,
};
const fmt = (n: number) => n.toLocaleString("ro-RO", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const maskIban = (iban: string | null) =>
  !iban || iban.length <= 4 ? (iban ?? "") : `…${iban.slice(-4)}`;

export function ReconModal({ companyId, companyName, period, onClose }:
  { companyId: string; companyName: string; period: string; onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const statements = useQuery({ queryKey: ["bank-stmts", companyId, period], queryFn: () => bankApi.statements(companyId, period) });
  const txns = useQuery({ queryKey: ["bank-txns", companyId, period], queryFn: () => bankApi.transactions(companyId, period) });

  const [linkingTxn, setLinkingTxn] = useState<string | null>(null);
  const invalidateRecon = () => {
    void qc.invalidateQueries({ queryKey: ["bank-txns", companyId, period] });
    void qc.invalidateQueries({ queryKey: ["recon-summary", period] });
    void qc.invalidateQueries({ queryKey: ["open-invoices", companyId, period] });
    void qc.invalidateQueries({ queryKey: ["doc-status", companyId, period] });
  };

  const setReq = useMutation({
    mutationFn: ({ id, requiresDocument }: { id: string; requiresDocument: boolean }) =>
      bankApi.setRequirement(companyId, id, requiresDocument),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["bank-txns", companyId, period] }),
  });

  const match = useMutation({
    mutationFn: ({ txnId, invoiceId }: { txnId: string; invoiceId: string }) => bankApi.match(companyId, txnId, invoiceId),
    onSuccess: () => { invalidateRecon(); setLinkingTxn(null); },
    onError: (e: unknown) => window.alert(`${t("recon.linkFailed")}: ${e instanceof Error ? e.message : String(e)}`),
  });
  const unmatch = useMutation({
    mutationFn: ({ txnId, invoiceId }: { txnId: string; invoiceId: string }) => bankApi.unmatch(companyId, txnId, invoiceId),
    onSuccess: invalidateRecon,
  });

  const list = txns.data ?? [];
  const missing = list.filter((tx) => tx.requiresDocument && !tx.matched);

  const requestFromClient = () => window.alert(t("recon.requestPreview"));

  return (
    <div style={overlay} onClick={onClose}>
      <div className="card" style={{ width: 1360, maxWidth: "98vw", maxHeight: "92vh", overflowX: "hidden", overflowY: "auto" }} onClick={(e) => e.stopPropagation()}>
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
            <span style={{ color: "var(--text-muted)" }}>{maskIban(s.accountIban)}</span>
            <span style={{ color: "var(--text-muted)" }}>
              {s.openingBalance != null ? fmt(s.openingBalance) : "—"} → {s.closingBalance != null ? fmt(s.closingBalance) : "—"}
            </span>
            <span style={{ color: s.crossCheckOk ? "#166534" : "#991b1b" }}>{s.crossCheckOk ? "✓" : "⚠"} {s.status}</span>
          </div>
        ))}

        {missing.length > 0 && (
          <div style={{ border: "1px dashed #dc2626", background: "#fef2f2", borderRadius: 10, padding: 12, margin: "12px 0" }}>
            <div style={{ color: "#991b1b", fontWeight: 600, fontSize: 13, marginBottom: 8 }}>
              ⚠ {t("recon.missingTitle")} — {missing.length}
            </div>
            {missing.map((m) => (
              <div key={m.id} style={{ display: "flex", justifyContent: "space-between", fontSize: 13, padding: "3px 0" }}>
                <span><b>{m.txnDate}</b> · {m.partnerName ?? "—"} <span style={{ color: "var(--text-muted)" }}>({m.category ?? "—"})</span></span>
                <span>{fmt(Math.abs(m.amount))} RON</span>
              </div>
            ))}
          </div>
        )}

        <table style={{ width: "100%", tableLayout: "fixed", borderCollapse: "collapse", marginTop: 12 }}>
          <colgroup>
            <col style={{ width: "8%" }} />
            <col style={{ width: "27%" }} />
            <col style={{ width: "10%" }} />
            <col style={{ width: "22%" }} />
            <col style={{ width: "16%" }} />
            <col style={{ width: "9%" }} />
            <col style={{ width: "8%" }} />
          </colgroup>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8 }}>{t("recon.date")}</th>
              <th style={{ padding: 8 }}>{t("recon.partner")}</th>
              <th style={{ padding: 8, textAlign: "right" }}>{t("recon.amount")}</th>
              <th style={{ padding: 8 }}>{t("recon.document")}</th>
              <th style={{ padding: 8 }}>{t("recon.reason")}</th>
              <th style={{ padding: 8 }}>{t("recon.decidedBy")}</th>
              <th style={{ padding: 8, textAlign: "center" }}>{t("recon.accountantSets")}</th>
            </tr>
          </thead>
          <tbody>
            {list.map((tx) => (
              <tr key={tx.id} style={{ borderTop: "1px solid var(--border)", background: tx.requiresDocument && !tx.matched ? "#fff7f6" : undefined }}>
                <td style={{ padding: 8, whiteSpace: "nowrap" }}>
                  {tx.txnDate}
                  {tx.txnDate.slice(0, 7) !== period.slice(0, 7) && (
                    <span title={t("recon.txnOutsidePeriod")} style={{ color: "#d97706", marginLeft: 4 }}>⚠</span>
                  )}
                </td>
                <td style={{ padding: 8, overflowWrap: "anywhere" }}>
                  <b>{tx.partnerName ?? "—"}</b>
                  <div style={{ color: "var(--text-muted)", fontSize: 12 }}>{[tx.description, maskIban(tx.partnerIban)].filter(Boolean).join(" · ")}</div>
                </td>
                <td style={{ padding: 8, textAlign: "right", fontVariantNumeric: "tabular-nums", color: tx.amount < 0 ? "inherit" : "#166534" }}>
                  {tx.amount < 0 ? "-" : "+"}{fmt(Math.abs(tx.amount))}
                </td>
                <td style={{ padding: 8, fontSize: 12, overflowWrap: "anywhere" }}>
                  {tx.matched ? (
                    <div>
                      {tx.matchedInvoices.map((mi) => (
                        <div key={mi.invoiceId} style={{ color: "#166534" }}>
                          ✓ {mi.filename ?? "factura"}{" "}
                          <button onClick={() => unmatch.mutate({ txnId: tx.id, invoiceId: mi.invoiceId })}
                            style={{ border: "none", background: "none", color: "#991b1b", cursor: "pointer" }}>✕</button>
                        </div>
                      ))}
                    </div>
                  ) : tx.requiresDocument ? (
                    <div>
                      <span style={{ color: "#991b1b" }}>⚠ {t("recon.needsDoc")}</span>{" "}
                      <button onClick={() => setLinkingTxn(tx.id)}>{t("recon.link")}</button>
                    </div>
                  ) : (
                    <span style={{ color: "var(--text-muted)" }}>{t("recon.notNeeded")}</span>
                  )}
                </td>
                <td style={{ padding: 8, fontSize: 12, color: "var(--text-muted)" }}>
                  {tx.reason}
                  {tx.decisionSource === "LEARNED_RULE" || tx.decisionSource === "ACCOUNTANT_SET"
                    ? <span style={{ marginLeft: 6, background: "#ede9fe", color: "#6d28d9", borderRadius: 999, padding: "1px 6px", fontSize: 10 }}>✓ {t("recon.remembered")}</span>
                    : null}
                </td>
                <td style={{ padding: 8, fontSize: 12 }}>
                  {tx.decisionSource ? t(`recon.source.${tx.decisionSource}`) : "—"}
                </td>
                <td style={{ padding: 8, textAlign: "center", whiteSpace: "nowrap" }}>
                  <button
                    disabled={setReq.isPending}
                    onClick={() => setReq.mutate({ id: tx.id, requiresDocument: true })}
                    title={t("recon.needsDoc")}
                    aria-label={t("recon.needsDoc")}
                    aria-pressed={tx.requiresDocument}
                    style={{
                      padding: "3px 8px", marginRight: 5, fontSize: 15, lineHeight: 1, borderRadius: 7,
                      cursor: "pointer",
                      border: `1px solid ${tx.requiresDocument ? "#dc2626" : "var(--border)"}`,
                      background: tx.requiresDocument ? "#fee2e2" : "transparent",
                      color: tx.requiresDocument ? "#991b1b" : "var(--text-muted)",
                    }}
                  >📎</button>
                  <button
                    disabled={setReq.isPending}
                    onClick={() => setReq.mutate({ id: tx.id, requiresDocument: false })}
                    title={t("recon.noDoc")}
                    aria-label={t("recon.noDoc")}
                    aria-pressed={!tx.requiresDocument}
                    style={{
                      padding: "3px 8px", fontSize: 15, lineHeight: 1, borderRadius: 7,
                      cursor: "pointer",
                      border: `1px solid ${!tx.requiresDocument ? "#16a34a" : "var(--border)"}`,
                      background: !tx.requiresDocument ? "#dcfce7" : "transparent",
                      color: !tx.requiresDocument ? "#166534" : "var(--text-muted)",
                    }}
                  >⊘</button>
                </td>
              </tr>
            ))}
            {list.length === 0 && (
              <tr><td colSpan={7} style={{ padding: 8, color: "var(--text-muted)" }}>—</td></tr>
            )}
          </tbody>
        </table>

        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8, marginTop: 14 }}>
          <button onClick={onClose}>{t("recon.notNeeded") && "Close"}</button>
          <button className="primary" disabled={missing.length === 0} onClick={requestFromClient}>
            {t("recon.requestClient")} ({missing.length})
          </button>
        </div>
      </div>

      {(() => {
        const linkTx = linkingTxn ? list.find((x) => x.id === linkingTxn) : undefined;
        return linkTx ? (
          <LinkInvoiceModal
            companyId={companyId}
            period={period}
            tx={linkTx}
            pending={match.isPending}
            onPick={(invoiceId) => match.mutate({ txnId: linkTx.id, invoiceId })}
            onClose={() => setLinkingTxn(null)}
          />
        ) : null;
      })()}
    </div>
  );
}
