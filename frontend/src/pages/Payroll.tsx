import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { payrollApi, type PayrollRow } from "../api/payroll";
import { usePeriod } from "../lib/period";
import { useCompanyFocus } from "../lib/useCompanyFocus";
import { Icon } from "../components/Icon";
import { PayrollEmailModal, type PayrollTarget } from "../components/PayrollEmailModal";
import { PayrollLogModal } from "../components/PayrollLogModal";
import { DocumentManagerModal } from "../components/DocumentManagerModal";

const dmy = (iso: string) => new Date(iso).toLocaleDateString("ro-RO", { day: "numeric", month: "short" });

/** MOD-08 Payroll — monthly hub list (Console B skin): manage payroll docs per company, send the
 *  standard email with attachments, track email status. Salary data is firm-staff only. */
export function Payroll() {
  const { t } = useTranslation();
  const { period } = usePeriod();
  const { focusCompany, focusRef, openModal } = useCompanyFocus();
  const qc = useQueryClient();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [sendList, setSendList] = useState<PayrollTarget[] | null>(null);
  const [logFor, setLogFor] = useState<{ id: string; name: string } | null>(null);
  const [manageFor, setManageFor] = useState<{ id: string; name: string } | null>(null);

  const companies = useQuery({ queryKey: ["companies"], queryFn: companiesApi.list });
  const payroll = useQuery({ queryKey: ["payroll", period], queryFn: () => payrollApi.list(period) });
  const rowBy = new Map<string, PayrollRow>((payroll.data ?? []).map((r) => [r.companyId, r]));

  const rows = companies.data ?? [];
  const selectableIds = rows.filter((c) => (rowBy.get(c.id)?.documents.length ?? 0) > 0).map((c) => c.id);
  const allSelected = selectableIds.length > 0 && selectableIds.every((id) => selected.has(id));

  useEffect(() => { setSelected(new Set()); }, [period]);

  // Deep-link from the dashboard (?company=&open=1): open that company's document manager once loaded.
  const autoOpened = useRef(false);
  useEffect(() => {
    if (!openModal || autoOpened.current) return;
    const c = (companies.data ?? []).find((x) => x.id === focusCompany);
    if (c) { autoOpened.current = true; setManageFor({ id: c.id, name: c.legalName }); }
  }, [openModal, focusCompany, companies.data]);

  const toggle = (id: string) => setSelected((p) => { const n = new Set(p); n.has(id) ? n.delete(id) : n.add(id); return n; });
  const toggleAll = () => setSelected(allSelected ? new Set() : new Set(selectableIds));
  const nameOf = (id: string) => rows.find((c) => c.id === id)?.legalName ?? id;
  const target = (id: string): PayrollTarget =>
    ({ companyId: id, companyName: nameOf(id), documents: rowBy.get(id)?.documents ?? [] });

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div>
        <div style={{ color: "var(--text-secondary)", fontSize: 12.5 }}>{t("payroll.crumb")}</div>
        <h2 style={{ margin: "2px 0 0", fontSize: 21, letterSpacing: "-0.01em" }}>{t("payroll.title")}</h2>
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
        <div style={{ minWidth: 820 }}>
          <div style={{ ...gridRow, background: "var(--th-bg)", ...thText }}>
            <div><input type="checkbox" checked={allSelected} disabled={selectableIds.length === 0} onChange={toggleAll} title={t("email.selectAll")} /></div>
            <div>{t("documents.company")}</div>
            <div>{t("payroll.documents")}</div>
            <div>{t("statements.lastSent")}</div>
          </div>

          {rows.map((c) => {
            const r = rowBy.get(c.id);
            const docs = r?.documents ?? [];
            const selectable = docs.length > 0;
            const manage = () => setManageFor({ id: c.id, name: c.legalName });
            return (
              <div key={c.id} ref={c.id === focusCompany ? focusRef : undefined} style={{ ...gridRow, borderTop: "1px solid var(--hair)", background: (c.id === focusCompany || selected.has(c.id)) ? "var(--row-active)" : undefined, boxShadow: c.id === focusCompany ? "inset 3px 0 0 var(--primary)" : undefined }}>
                <div>{selectable ? <input type="checkbox" checked={selected.has(c.id)} onChange={() => toggle(c.id)} /> : <span style={{ color: "var(--text-faint)" }}>·</span>}</div>
                <div>
                  <div style={{ fontWeight: 600 }}>{c.legalName}</div>
                  <div className="mono" style={{ color: "var(--text-muted)", fontSize: 11 }}>{c.cui}{c.locality ? ` · ${c.locality}` : ""}</div>
                </div>
                <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
                  {docs.length === 0
                    ? <button className="pill round danger" style={chipBtn} onClick={manage}>{t("payroll.missing")}</button>
                    : docs.map((d) => (
                        <button key={d.id} className="pill round muted" title={t("files.manage")} onClick={manage}
                          style={{ ...chipBtn, maxWidth: 200, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                          <Icon name="doc" size={10} style={{ verticalAlign: "-1px", marginRight: 3 }} />{d.filename}
                        </button>
                      ))}
                </div>
                <div>
                  {r?.lastSentAt
                    ? <button className="pill teal round" style={pillBtn} title={t("statements.lastSent")} onClick={() => setLogFor({ id: c.id, name: c.legalName })}>
                        <Icon name="mail" size={11} style={{ verticalAlign: "-1px", marginRight: 4 }} />{dmy(r.lastSentAt)}{r.sentCount > 1 ? ` · ${r.sentCount}` : ""}
                      </button>
                    : <button style={neverBtn} title={t("statements.lastSent")} onClick={() => setLogFor({ id: c.id, name: c.legalName })}>{t("taxes.neverSent")} · <u>{t("taxes.sendShort")}</u></button>}
                </div>
              </div>
            );
          })}
          {payroll.data && rows.length === 0 && <div style={{ padding: 14, color: "var(--text-muted)" }}>{t("taxes.noCompanies")}</div>}
        </div>
      </div>

      {manageFor && <DocumentManagerModal companyId={manageFor.id} companyName={manageFor.name} period={period}
        type="PAYROLL" title={t("payroll.documents")} subtitle={t("payroll.crumb")}
        accept="application/pdf,image/png,image/jpeg,image/webp"
        onClose={() => setManageFor(null)} onChanged={() => qc.invalidateQueries({ queryKey: ["payroll", period] })} />}
      {sendList && <PayrollEmailModal targets={sendList} period={period} onClose={() => setSendList(null)} />}
      {logFor && <PayrollLogModal companyId={logFor.id} companyName={logFor.name} period={period}
        onClose={() => setLogFor(null)}
        onCompose={() => setSendList([target(logFor.id)])} />}
    </div>
  );
}

const gridRow: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "30px minmax(220px,1.6fr) minmax(240px,2fr) 150px",
  alignItems: "center", gap: 10, padding: "10px 16px",
};
const thText: React.CSSProperties = { fontSize: 9.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "#8a9794" };
const chipBtn: React.CSSProperties = { cursor: "pointer", font: "inherit" };
const pillBtn: React.CSSProperties = { cursor: "pointer", border: "1px solid var(--teal-chip-bd)", font: "inherit" };
const neverBtn: React.CSSProperties = { background: "none", border: "1px dashed var(--border)", borderRadius: 999, padding: "1px 8px", fontSize: 11, color: "var(--primary-dark)", cursor: "pointer" };
