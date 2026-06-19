import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { documentsSummaryApi, type CompanyDocSummary } from "../api/documents";
import { reconciliationApi } from "../api/bank";
import { MonthBar } from "../components/MonthBar";
import { FilesModal } from "../components/FilesModal";
import { ReconModal } from "../components/ReconModal";
import { SendReminderModal, type ReminderTarget } from "../components/SendReminderModal";

type DotKind = "green" | "orange" | "red";
const DOT_COLOR: Record<DotKind, string> = { green: "#16a34a", orange: "#fb923c", red: "#dc2626" };
type Payment = "NONE" | "PARTIAL" | "COMPLETE";

/**
 * Row health dot = payment/matching of the company's invoices/receipts for the period.
 * green  = all invoices fully matched/paid
 * orange = partially matched, OR invoices present but no bank statement yet (can't match → waiting)
 * red    = has a statement but nothing matched, OR nothing uploaded at all
 */
function rowStatus(s: CompanyDocSummary | undefined, payment: Payment): { kind: DotKind; key: string } {
  const inv = s?.invoiceReceiptCount ?? 0;
  const hasBank = s?.hasBankStatement ?? false;
  if (inv === 0) return { kind: "red", key: "statements.dot.nothing" };
  if (payment === "COMPLETE") return { kind: "green", key: "statements.dot.complete" };
  if (payment === "PARTIAL") return { kind: "orange", key: "statements.dot.partial" };
  return hasBank
    ? { kind: "red", key: "statements.dot.unmatched" }
    : { kind: "orange", key: "statements.dot.waiting" };
}

const iconBtn: React.CSSProperties = {
  background: "none", border: "1px solid var(--border)", borderRadius: 8,
  cursor: "pointer", fontSize: 15, lineHeight: 1, padding: "5px 8px", marginLeft: 6,
};

type ChipKind = "green" | "red" | "gray";
const CHIP: Record<ChipKind, { bg: string; fg: string; bd: string }> = {
  green: { bg: "#dcfce7", fg: "#166534", bd: "#bbf7d0" },
  red: { bg: "#fee2e2", fg: "#991b1b", bd: "#fecaca" },
  gray: { bg: "#f3f4f6", fg: "#6b7280", bd: "#e5e7eb" },
};

function Chip({ label, kind, title, onClick }:
  { label: React.ReactNode; kind: ChipKind; title: string; onClick?: () => void }) {
  const c = CHIP[kind];
  const style: React.CSSProperties = {
    background: c.bg, color: c.fg, border: `1px solid ${c.bd}`, borderRadius: 999,
    padding: "1px 8px", fontSize: 12, marginRight: 4, display: "inline-block", font: "inherit",
  };
  if (onClick) {
    return (
      <button type="button" title={title} aria-label={title} onClick={onClick}
        style={{ ...style, cursor: "pointer" }}>
        {label}
      </button>
    );
  }
  return <span title={title} aria-label={title} style={style}>{label}</span>;
}

/** Statements & invoices — compact monthly company list (follows the prototype). */
export function Statements() {
  const { t } = useTranslation();
  const [period, setPeriod] = useState(() => new Date().toISOString().slice(0, 7) + "-01");
  const [filesFor, setFilesFor] = useState<{ id: string; name: string } | null>(null);
  const [reconFor, setReconFor] = useState<{ id: string; name: string } | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [sendList, setSendList] = useState<ReminderTarget[] | null>(null);

  const companies = useQuery({ queryKey: ["companies"], queryFn: companiesApi.list });
  const summary = useQuery({
    queryKey: ["doc-summary", period],
    queryFn: () => documentsSummaryApi.summary(period),
  });
  const completeness = useQuery({
    queryKey: ["recon-summary", period],
    queryFn: () => reconciliationApi.summary(period),
  });
  const completenessBy = new Map((completeness.data ?? []).map((c) => [c.companyId, c.completeness]));
  const paymentBy = new Map((completeness.data ?? []).map((c) => [c.companyId, c.payment]));
  const missingTxnBy = new Map((completeness.data ?? []).map((c) => [c.companyId, c.missingTxnCount]));
  const unmatchedBy = new Map((completeness.data ?? []).map((c) => [c.companyId, c.unmatchedInvoiceCount]));
  const byCompany = new Map((summary.data ?? []).map((s) => [s.companyId, s]));

  // A company needs a reminder when anything for the period is still missing or incomplete.
  const needsReminder = (id: string) => {
    const s = byCompany.get(id);
    const cs = completenessBy.get(id) ?? "NOT_STARTED";
    return !(s?.hasBankStatement ?? false) || !(s?.hasInvoiceOrReceipt ?? false) || cs !== "COMPLETE";
  };

  const rows = companies.data ?? [];
  const selectableIds = rows.filter((c) => needsReminder(c.id)).map((c) => c.id);
  const allSelected = selectableIds.length > 0 && selectableIds.every((id) => selected.has(id));

  // Reset the selection whenever the period changes — the missing set is period-specific.
  useEffect(() => { setSelected(new Set()); }, [period]);

  const toggle = (id: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  const toggleAll = () =>
    setSelected(allSelected ? new Set() : new Set(selectableIds));
  const nameOf = (id: string) => rows.find((c) => c.id === id)?.legalName ?? id;
  const target = (id: string): ReminderTarget => {
    const s = byCompany.get(id);
    return {
      id, name: nameOf(id),
      hasBankStatement: s?.hasBankStatement ?? false,
      hasInvoiceOrReceipt: s?.hasInvoiceOrReceipt ?? false,
    };
  };
  const sendSelected = () => setSendList([...selected].map(target));

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="card">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <div>
            <div style={{ color: "var(--text-muted)", fontSize: 12.5 }}>{t("statements.crumb")}</div>
            <h1 style={{ margin: "2px 0 0" }}>{t("documents.title")}</h1>
          </div>
          <MonthBar value={period} onChange={setPeriod} />
        </div>
      </div>

      {/* Bulk bar — appears only when at least one company is selected. */}
      {selected.size > 0 && (
        <div className="card" style={{
          display: "flex", alignItems: "center", justifyContent: "space-between",
          padding: "10px 14px", borderColor: "var(--primary)",
        }}>
          <span style={{ fontSize: 13.5 }}>✓ <b>{selected.size}</b> {t("email.selected", { n: selected.size })}</span>
          <div style={{ display: "flex", gap: 8 }}>
            <button onClick={() => setSelected(new Set())}>{t("email.clear")}</button>
            <button className="primary" onClick={sendSelected}>✉ {t("email.sendN", { n: selected.size })}</button>
          </div>
        </div>
      )}

      <div className="card">
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8, width: 34 }}>
                <input type="checkbox" checked={allSelected} onChange={toggleAll}
                  disabled={selectableIds.length === 0} title={t("email.selectAll")}
                  style={{ cursor: selectableIds.length === 0 ? "default" : "pointer" }} />
              </th>
              <th style={{ padding: 8, width: 24 }} title={t("statements.completeness")} />
              <th style={{ padding: 8 }}>{t("documents.company")}</th>
              <th style={{ padding: 8 }}>{t("statements.bankStatement")}</th>
              <th style={{ padding: 8 }}>{t("statements.invoices")}</th>
              <th style={{ padding: 8, textAlign: "right" }}>{t("statements.actions")}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => {
              const s = byCompany.get(c.id);
              const hasBank = s?.hasBankStatement ?? false;
              const selectable = needsReminder(c.id);
              const st = rowStatus(s, (paymentBy.get(c.id) ?? "NONE") as Payment);
              return (
                <tr key={c.id} style={{
                  borderTop: "1px solid var(--border)",
                  background: selected.has(c.id) ? "var(--primary-light, #eef2ff)" : undefined,
                }}>
                  <td style={{ padding: 8, textAlign: "center" }}>
                    {selectable
                      ? <input type="checkbox" checked={selected.has(c.id)} onChange={() => toggle(c.id)}
                          style={{ cursor: "pointer" }} />
                      : <span style={{ color: "var(--text-muted)" }}>·</span>}
                  </td>
                  <td style={{ padding: 8, textAlign: "center" }}>
                    <span role="img" aria-label={t(st.key)} title={t(st.key)}
                      style={{ display: "inline-block", width: 12, height: 12, borderRadius: "50%", background: DOT_COLOR[st.kind] }} />
                  </td>
                  <td style={{ padding: 8 }}>
                    <b>{c.legalName}</b>
                    <div style={{ color: "var(--text-muted)", fontSize: 12 }}>{c.cui}{c.locality ? ` · ${c.locality}` : ""}</div>
                  </td>
                  <td style={{ padding: 8 }}>
                    {hasBank
                      ? <Chip kind="green" label={s?.bankStatementCount ?? 0} title={t("statements.chip.statements")}
                          onClick={() => setFilesFor({ id: c.id, name: c.legalName })} />
                      : <Chip kind="red" label={t("statements.missing")} title={t("statements.chip.noStatement")}
                          onClick={() => setFilesFor({ id: c.id, name: c.legalName })} />}
                  </td>
                  <td style={{ padding: 8 }}>{(() => {
                    const present = s?.invoiceReceiptCount ?? 0;
                    const missing = missingTxnBy.get(c.id) ?? 0;
                    const noMatch = unmatchedBy.get(c.id) ?? 0;
                    if (present === 0 && missing === 0 && noMatch === 0) {
                      return <span style={{ color: "var(--text-muted)" }}>—</span>;
                    }
                    const openFiles = () => setFilesFor({ id: c.id, name: c.legalName });
                    return (
                      <>
                        {present > 0 && <Chip kind="green" label={present} title={t("statements.chip.present")} onClick={openFiles} />}
                        {missing > 0 && <Chip kind="red" label={missing} title={t("statements.chip.missing")} onClick={openFiles} />}
                        {noMatch > 0 && <Chip kind="gray" label={noMatch} title={t("statements.chip.noMatch")} onClick={openFiles} />}
                      </>
                    );
                  })()}</td>
                  <td style={{ padding: 8, textAlign: "right", whiteSpace: "nowrap" }}>
                    <button style={iconBtn} title={t("statements.files")}
                      onClick={() => setFilesFor({ id: c.id, name: c.legalName })}>📁</button>
                    <button title={t("statements.viewTransactions")} disabled={!hasBank}
                      onClick={() => setReconFor({ id: c.id, name: c.legalName })}
                      style={{ ...iconBtn, opacity: hasBank ? 1 : 0.35, cursor: hasBank ? "pointer" : "default" }}>⇄</button>
                    <button title={t("email.send")} disabled={!selectable}
                      onClick={() => setSendList([target(c.id)])}
                      style={{ ...iconBtn, opacity: selectable ? 1 : 0.35, cursor: selectable ? "pointer" : "default" }}>✉</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {filesFor && (
        <FilesModal companyId={filesFor.id} companyName={filesFor.name} period={period} onClose={() => setFilesFor(null)} />
      )}
      {reconFor && (
        <ReconModal companyId={reconFor.id} companyName={reconFor.name} period={period} onClose={() => setReconFor(null)} />
      )}
      {sendList && (
        <SendReminderModal companies={sendList} period={period} onClose={() => setSendList(null)} />
      )}
    </div>
  );
}
