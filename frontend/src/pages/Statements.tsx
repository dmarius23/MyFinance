import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { documentsSummaryApi, remindersApi, type CompanyDocSummary } from "../api/documents";
import { reconciliationApi } from "../api/bank";
import { usePeriod } from "../lib/period";
import { Icon } from "../components/Icon";
import { FilesModal } from "../components/FilesModal";
import { SendReminderModal, type ReminderTarget } from "../components/SendReminderModal";
import { ReminderLogModal } from "../components/ReminderLogModal";

const dmy = (iso: string) => new Date(iso).toLocaleDateString("ro-RO", { day: "numeric", month: "short" });

type DotKind = "green" | "orange" | "red";
const DOT_COLOR: Record<DotKind, string> = { green: "var(--dot-green)", orange: "var(--dot-orange)", red: "var(--dot-red)" };
type Payment = "NONE" | "PARTIAL" | "COMPLETE";

/** Row health dot = payment/matching of the company's invoices/receipts for the period. */
function rowStatus(s: CompanyDocSummary | undefined, payment: Payment): { kind: DotKind; key: string } {
  const inv = s?.invoiceReceiptCount ?? 0;
  const hasBank = s?.hasBankStatement ?? false;
  if (inv === 0) return { kind: "red", key: "statements.dot.nothing" };
  if (payment === "COMPLETE") return { kind: "green", key: "statements.dot.complete" };
  if (payment === "PARTIAL") return { kind: "orange", key: "statements.dot.partial" };
  return hasBank ? { kind: "red", key: "statements.dot.unmatched" } : { kind: "orange", key: "statements.dot.waiting" };
}

function ClickPill({ label, kind, title, onClick }:
  { label: React.ReactNode; kind: "ok" | "danger" | "muted" | "warn"; title: string; onClick?: () => void }) {
  const cls = `pill round ${kind}`;
  return onClick
    ? <button type="button" className={cls} title={title} onClick={onClick} style={{ cursor: "pointer", marginRight: 4, font: "inherit" }}>{label}</button>
    : <span className={cls} title={title} style={{ marginRight: 4 }}>{label}</span>;
}

/** Statements & invoices — monthly hub list, Console (B) skin. */
export function Statements() {
  const { t } = useTranslation();
  const { period } = usePeriod();
  const navigate = useNavigate();
  const goReconcile = (id: string) => navigate(`/statements/${id}/reconcile`);
  const [filesFor, setFilesFor] = useState<{ id: string; name: string; cui: string } | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [sendList, setSendList] = useState<ReminderTarget[] | null>(null);
  const [logFor, setLogFor] = useState<{ id: string; name: string } | null>(null);

  const companies = useQuery({ queryKey: ["companies"], queryFn: companiesApi.list });
  const summary = useQuery({ queryKey: ["doc-summary", period], queryFn: () => documentsSummaryApi.summary(period) });
  const completeness = useQuery({ queryKey: ["recon-summary", period], queryFn: () => reconciliationApi.summary(period) });
  const reminders = useQuery({ queryKey: ["doc-reminders", period], queryFn: () => remindersApi.list(period) });
  const reminderBy = new Map((reminders.data ?? []).map((r) => [r.companyId, r]));

  const completenessBy = new Map((completeness.data ?? []).map((c) => [c.companyId, c.completeness]));
  const paymentBy = new Map((completeness.data ?? []).map((c) => [c.companyId, c.payment]));
  const missingTxnBy = new Map((completeness.data ?? []).map((c) => [c.companyId, c.missingTxnCount]));
  const unmatchedBy = new Map((completeness.data ?? []).map((c) => [c.companyId, c.unmatchedInvoiceCount]));
  const byCompany = new Map((summary.data ?? []).map((s) => [s.companyId, s]));

  const needsReminder = (id: string) => {
    const s = byCompany.get(id);
    const cs = completenessBy.get(id) ?? "NOT_STARTED";
    return !(s?.hasBankStatement ?? false) || !(s?.hasInvoiceOrReceipt ?? false) || cs !== "COMPLETE";
  };

  const rows = companies.data ?? [];
  const selectableIds = rows.filter((c) => needsReminder(c.id)).map((c) => c.id);
  const allSelected = selectableIds.length > 0 && selectableIds.every((id) => selected.has(id));
  const incomplete = selectableIds.length;

  useEffect(() => { setSelected(new Set()); }, [period]);

  const toggle = (id: string) => setSelected((p) => { const n = new Set(p); n.has(id) ? n.delete(id) : n.add(id); return n; });
  const toggleAll = () => setSelected(allSelected ? new Set() : new Set(selectableIds));
  const nameOf = (id: string) => rows.find((c) => c.id === id)?.legalName ?? id;
  const target = (id: string): ReminderTarget => {
    const s = byCompany.get(id);
    return { id, name: nameOf(id), hasBankStatement: s?.hasBankStatement ?? false, hasInvoiceOrReceipt: s?.hasInvoiceOrReceipt ?? false };
  };

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end" }}>
        <div>
          <div style={{ color: "var(--text-secondary)", fontSize: 12.5 }}>{t("statements.crumb")}</div>
          <h2 style={{ margin: "2px 0 0", fontSize: 21, letterSpacing: "-0.01em" }}>{t("documents.title")}</h2>
        </div>
        {incomplete > 0 && (
          <div style={{ fontSize: 12, color: "var(--text-secondary)" }}>
            <span style={{ display: "inline-block", width: 8, height: 8, borderRadius: "50%", background: "var(--dot-orange)", marginRight: 5 }} />
            {incomplete} {t("statements.legendIncomplete")}
          </div>
        )}
      </div>

      {selected.size > 0 && (
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", background: "var(--chrome-bg)", borderRadius: 10, padding: "9px 14px" }}>
          <span style={{ fontSize: 13.5, color: "var(--chrome-text)" }}><b style={{ color: "var(--primary)" }}>{selected.size}</b> {t("email.selected", { n: selected.size })}</span>
          <div style={{ display: "flex", gap: 8 }}>
            <button onClick={() => setSelected(new Set())} style={{ background: "var(--chrome-active)", color: "var(--chrome-text)", border: "1px solid #2a3a37" }}>{t("email.clear")}</button>
            <button className="primary" onClick={() => setSendList([...selected].map(target))}><Icon name="mail" size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />{t("email.sendN", { n: selected.size })}</button>
          </div>
        </div>
      )}

      <div className="card" style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ minWidth: 900 }}>
          <div style={{ ...gridRow, background: "var(--th-bg)", ...thText }}>
            <div><input type="checkbox" checked={allSelected} disabled={selectableIds.length === 0} onChange={toggleAll} title={t("email.selectAll")} /></div>
            <div />
            <div>{t("documents.company")}</div>
            <div>{t("statements.bankStatement")}</div>
            <div>{t("statements.invoices")}</div>
            <div>{t("statements.completeness")}</div>
            <div>{t("statements.lastSent")}</div>
          </div>

          {rows.map((c) => {
            const s = byCompany.get(c.id);
            const hasBank = s?.hasBankStatement ?? false;
            const selectable = needsReminder(c.id);
            const st = rowStatus(s, (paymentBy.get(c.id) ?? "NONE") as Payment);
            const cpl = completenessBy.get(c.id) ?? "NOT_STARTED";
            const present = s?.invoiceReceiptCount ?? 0;
            const missing = missingTxnBy.get(c.id) ?? 0;
            const noMatch = unmatchedBy.get(c.id) ?? 0;
            const openFiles = () => setFilesFor({ id: c.id, name: c.legalName, cui: c.cui });
            return (
              <div key={c.id} style={{ ...gridRow, borderTop: "1px solid var(--hair)", background: selected.has(c.id) ? "var(--row-active)" : undefined }}>
                <div>{selectable ? <input type="checkbox" checked={selected.has(c.id)} onChange={() => toggle(c.id)} /> : <span style={{ color: "var(--text-faint)" }}>·</span>}</div>
                <div><span role="img" aria-label={t(st.key)} title={t(st.key)} style={{ display: "inline-block", width: 8, height: 8, borderRadius: "50%", background: DOT_COLOR[st.kind] }} /></div>
                <div>
                  <button className="row-open" onClick={() => goReconcile(c.id)} title={t("statements.viewTransactions")}
                    style={{ fontWeight: 600, background: "none", border: "none", padding: 0, cursor: "pointer", color: "var(--text)", font: "inherit", textAlign: "left" }}>
                    {c.legalName}
                  </button>
                  <div className="mono" style={{ color: "var(--text-muted)", fontSize: 11 }}>{c.cui}{c.locality ? ` · ${c.locality}` : ""}</div>
                </div>
                <div>
                  {hasBank
                    ? <ClickPill kind="ok" label={t("statements.nStatements", { n: s?.bankStatementCount ?? 0 })} title={t("statements.chip.statements")} onClick={openFiles} />
                    : <ClickPill kind="danger" label={t("statements.missing")} title={t("statements.chip.noStatement")} onClick={openFiles} />}
                </div>
                <div>
                  {present === 0 && missing === 0 && noMatch === 0
                    ? <span style={{ color: "var(--text-faint)" }}>—</span>
                    : <>
                        {present > 0 && <ClickPill kind="ok" label={present} title={t("statements.chip.present")} onClick={openFiles} />}
                        {missing > 0 && <ClickPill kind="danger" label={t("statements.needsDocN", { n: missing })} title={t("statements.chip.missing")} onClick={openFiles} />}
                        {noMatch > 0 && <ClickPill kind="muted" label={noMatch} title={t("statements.chip.noMatch")} onClick={openFiles} />}
                      </>}
                </div>
                <div>
                  {cpl === "COMPLETE" ? <span className="pill round ok">{t("statements.cpl.complete")}</span>
                    : cpl === "PARTIAL" ? <span className="pill round warn">{t("statements.cpl.partial")}</span>
                    : <span className="pill round muted">{t("statements.cpl.notStarted")}</span>}
                </div>
                <div>
                  {(() => {
                    const r = reminderBy.get(c.id);
                    return r?.lastSentAt
                      ? <button className="pill teal round" style={pillBtn} title={t("statements.lastSent")} onClick={() => setLogFor({ id: c.id, name: c.legalName })}>
                          <Icon name="mail" size={11} style={{ verticalAlign: "-1px", marginRight: 4 }} />{dmy(r.lastSentAt)}{r.count > 1 ? ` · ${r.count}` : ""}
                        </button>
                      : <button style={neverBtn} title={t("statements.lastSent")} onClick={() => setLogFor({ id: c.id, name: c.legalName })}>{t("taxes.neverSent")} · <u>{t("taxes.sendShort")}</u></button>;
                  })()}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {filesFor && <FilesModal companyId={filesFor.id} companyName={filesFor.name} companyCui={filesFor.cui} period={period} onClose={() => setFilesFor(null)} />}
      {sendList && <SendReminderModal companies={sendList} period={period} onClose={() => setSendList(null)} />}
      {logFor && <ReminderLogModal companyId={logFor.id} companyName={logFor.name} period={period}
        onClose={() => setLogFor(null)}
        onCompose={() => setSendList([target(logFor.id)])} />}
    </div>
  );
}

const gridRow: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "30px 24px minmax(220px,1.6fr) 120px 160px 104px 130px",
  alignItems: "center", gap: 10, padding: "10px 16px",
};
const thText: React.CSSProperties = { fontSize: 9.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "#8a9794" };
const pillBtn: React.CSSProperties = { cursor: "pointer", border: "1px solid var(--teal-chip-bd)", font: "inherit" };
const neverBtn: React.CSSProperties = { background: "none", border: "1px dashed var(--border)", borderRadius: 999, padding: "1px 8px", fontSize: 11, color: "var(--primary-dark)", cursor: "pointer" };
