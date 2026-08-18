import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { payrollApi, type PayrollRow } from "../api/payroll";
import { usePeriod } from "../lib/period";
import { useCompanyFocus } from "../lib/useCompanyFocus";
import { Icon } from "../components/Icon";
import { ActionBtn, WhatsAppAction, RowActions, LastEmailCell, LastWhatsAppCell } from "../components/RowActions";
import { InfoTip } from "../components/InfoTip";
import { PayrollEmailModal, type PayrollTarget } from "../components/PayrollEmailModal";
import { PayrollLogModal } from "../components/PayrollLogModal";
import { DocumentManagerModal } from "../components/DocumentManagerModal";
import { WhatsAppModal } from "../components/WhatsAppModal";
import { WhatsAppBulkModal, type WhatsAppTarget } from "../components/WhatsAppBulkModal";
import { BulkActionBar } from "../components/BulkActionBar";
import { HeaderSummary } from "../components/HeaderSummary";
import { SyncMonthButton } from "../components/SyncMonthButton";
import { attachmentsNote } from "../lib/attachmentsNote";

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
  const [waFor, setWaFor] = useState<{ id: string; name: string } | null>(null);
  const [waBulk, setWaBulk] = useState<WhatsAppTarget[] | null>(null);

  const companies = useQuery({ queryKey: ["companies"], queryFn: companiesApi.list });
  const payroll = useQuery({ queryKey: ["payroll", period], queryFn: () => payrollApi.list(period) });
  const rowBy = new Map<string, PayrollRow>((payroll.data ?? []).map((r) => [r.companyId, r]));

  const rows = companies.data ?? [];
  const selectableIds = rows.filter((c) => (rowBy.get(c.id)?.documents.length ?? 0) > 0).map((c) => c.id);
  const allSelected = selectableIds.length > 0 && selectableIds.every((id) => selected.has(id));

  // Column-aligned "to resolve this month" counts, shown in a strip on top of the table.
  const docsN = (id: string) => rowBy.get(id)?.documents.length ?? 0;
  const missingDocs = rows.filter((c) => docsN(c.id) === 0).length;
  // "Missing email/WhatsApp" = companies with no last email/WhatsApp for this module + month.
  const noEmail = rows.filter((c) => !rowBy.get(c.id)?.lastSentAt).length;
  const noWa = rows.filter((c) => !rowBy.get(c.id)?.lastWhatsappAt).length;

  useEffect(() => { setSelected(new Set()); }, [period]);

  // Deep-link from the dashboard (?company=&open=1): open that company's document manager once loaded.
  const autoOpened = useRef(false);
  useEffect(() => {
    if (!openModal || autoOpened.current) return;
    const c = (companies.data ?? []).find((x) => x.id === focusCompany);
    if (c) { autoOpened.current = true; setManageFor({ id: c.id, name: c.legalName }); }
  }, [openModal, focusCompany, companies.data]);

  const toggle = (id: string) => setSelected((p) => { const n = new Set(p); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const toggleAll = () => setSelected(allSelected ? new Set() : new Set(selectableIds));
  const nameOf = (id: string) => rows.find((c) => c.id === id)?.legalName ?? id;
  const target = (id: string): PayrollTarget =>
    ({ companyId: id, companyName: nameOf(id), documents: rowBy.get(id)?.documents ?? [] });
  const waBody = (id: string) => payrollApi.emailBody(id, period).then((r) =>
    r.body + attachmentsNote(t("channel.attachments"), (rowBy.get(id)?.documents ?? []).map((d) => d.filename)));

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end", gap: 12 }}>
        <div>
          <div style={{ color: "var(--text-secondary)", fontSize: 12.5 }}>{t("payroll.crumb")}</div>
          <h2 style={{ margin: "2px 0 0", fontSize: 21, letterSpacing: "-0.01em" }}>{t("payroll.title")}</h2>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 16 }}>
          <HeaderSummary items={[
            { n: missingDocs, label: t("summary.missingDocs") },
            { n: noEmail, label: t("summary.noEmail") },
            { n: noWa, label: t("summary.noWhatsapp") },
          ]} />
          <SyncMonthButton type="PAYROLL" period={period} onDone={() => qc.invalidateQueries({ queryKey: ["payroll", period] })} />
        </div>
      </div>

      <BulkActionBar count={selected.size} label={t("email.selected", { n: selected.size })}
        onClear={() => setSelected(new Set())}
        onEmail={() => setSendList([...selected].map(target))}
        onWhatsapp={() => setWaBulk([...selected].map((id) => ({ companyId: id, companyName: nameOf(id) })))} />

      <div className="card" style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ minWidth: 880 }}>
          <div style={{ ...gridRow, background: "var(--th-bg)", ...thText }}>
            <div><input type="checkbox" checked={allSelected} disabled={selectableIds.length === 0} onChange={toggleAll} title={t("email.selectAll")} /></div>
            <div>{t("documents.company")}</div>
            <div>{t("payroll.documents")}</div>
            <div>{t("channel.lastEmail")}</div>
            <div>{t("channel.lastWhatsapp")}</div>
            <div style={{ textAlign: "right" }}>{t("channel.actions")}</div>
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
                <div>
                  {docs.length === 0
                    ? <span className="pill round danger">{t("payroll.missing")}</span>
                    : <InfoTip lines={docs.map((d) => d.filename)}>
                        <span className="pill round muted">
                          <Icon name="doc" size={10} style={{ verticalAlign: "-1px", marginRight: 4 }} />{docs.length}
                        </span>
                      </InfoTip>}
                </div>
                <div><LastEmailCell lastSentAt={r?.lastSentAt} count={r?.sentCount} onOpen={() => setLogFor({ id: c.id, name: c.legalName })} /></div>
                <div><LastWhatsAppCell lastSentAt={r?.lastWhatsappAt} count={r?.whatsappCount} onOpen={() => setWaFor({ id: c.id, name: c.legalName })} /></div>
                <div>
                  <RowActions>
                    <ActionBtn icon="upload" title={t("channel.upload")} onClick={manage} />
                    <ActionBtn icon="mail" title={t("channel.email")} onClick={() => setSendList([target(c.id)])} />
                    <WhatsAppAction onClick={() => setWaFor({ id: c.id, name: c.legalName })} />
                  </RowActions>
                </div>
              </div>
            );
          })}
          {payroll.data && rows.length === 0 && <div style={{ padding: 14, color: "var(--text-muted)" }}>{t("taxes.noCompanies")}</div>}
        </div>
      </div>

      {manageFor && <DocumentManagerModal companyId={manageFor.id} companyName={manageFor.name} period={period}
        type="PAYROLL" title={t("payroll.documents")}
        accept="application/pdf,image/png,image/jpeg,image/webp"
        onClose={() => setManageFor(null)} onChanged={() => qc.invalidateQueries({ queryKey: ["payroll", period] })} />}
      {sendList && <PayrollEmailModal targets={sendList} period={period} onClose={() => setSendList(null)} />}
      {waFor && <WhatsAppModal companyId={waFor.id} companyName={waFor.name} kind="PAYROLL" period={period}
        loadBody={() => waBody(waFor.id)}
        onClose={() => setWaFor(null)} />}
      {waBulk && <WhatsAppBulkModal targets={waBulk} kind="PAYROLL" period={period} loadBody={waBody}
        onClose={() => setWaBulk(null)} onSent={() => { setSelected(new Set()); qc.invalidateQueries({ queryKey: ["payroll", period] }); }} />}
      {logFor && <PayrollLogModal companyId={logFor.id} companyName={logFor.name} period={period}
        onClose={() => setLogFor(null)}
        onCompose={() => setSendList([target(logFor.id)])} />}
    </div>
  );
}

const gridRow: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "30px minmax(220px,2fr) 120px 120px 110px 120px",
  alignItems: "center", gap: 10, padding: "10px 16px",
};
const thText: React.CSSProperties = { fontSize: 9.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "#8a9794" };
