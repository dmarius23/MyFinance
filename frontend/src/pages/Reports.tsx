import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { reportsApi, type ReportRow } from "../api/reports";
import { usePeriod } from "../lib/period";
import { useCompanyFocus } from "../lib/useCompanyFocus";
import { Icon } from "../components/Icon";
import { ActionBtn, WhatsAppAction, RowActions, LastEmailCell, LastWhatsAppCell } from "../components/RowActions";
import { InfoTip } from "../components/InfoTip";
import { ReportChartsModal } from "../components/ReportChartsModal";
import { ReportEmailModal, type ReportTarget } from "../components/ReportEmailModal";
import { ReportLogModal } from "../components/ReportLogModal";
import { DocumentManagerModal } from "../components/DocumentManagerModal";
import { WhatsAppModal } from "../components/WhatsAppModal";
import { WhatsAppBulkModal, type WhatsAppTarget } from "../components/WhatsAppBulkModal";
import { BulkActionBar } from "../components/BulkActionBar";
import { HeaderSummary } from "../components/HeaderSummary";
import { SyncMonthButton } from "../components/SyncMonthButton";
import { attachmentsNote } from "../lib/attachmentsNote";

/** MOD-06 Reports — monthly hub: manage trial balance, download branded report, charts, email to rep. */
export function Reports() {
  const { t } = useTranslation();
  const { period } = usePeriod();
  const { focusCompany, focusRef, openModal } = useCompanyFocus();
  const qc = useQueryClient();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [chartsFor, setChartsFor] = useState<{ id: string; name: string } | null>(null);
  const [logFor, setLogFor] = useState<{ id: string; name: string } | null>(null);
  const [sendList, setSendList] = useState<ReportTarget[] | null>(null);
  const [manageFor, setManageFor] = useState<{ id: string; name: string } | null>(null);
  const [waFor, setWaFor] = useState<{ id: string; name: string } | null>(null);
  const [waBulk, setWaBulk] = useState<WhatsAppTarget[] | null>(null);

  const companies = useQuery({ queryKey: ["companies"], queryFn: companiesApi.list });
  const reports = useQuery({ queryKey: ["reports", period], queryFn: () => reportsApi.list(period) });
  const rowBy = new Map<string, ReportRow>((reports.data ?? []).map((r) => [r.companyId, r]));

  const rows = companies.data ?? [];
  const hasReport = (id: string) => !!rowBy.get(id)?.uploadedAt;
  const selectableIds = rows.filter((c) => hasReport(c.id)).map((c) => c.id);
  const allSelected = selectableIds.length > 0 && selectableIds.every((id) => selected.has(id));

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
  const target = (id: string): ReportTarget => ({ companyId: id, companyName: nameOf(id) });
  const waBody = (id: string) => reportsApi.emailBody(id, period).then((r) =>
    r.body + attachmentsNote(t("channel.attachments"), rowBy.get(id)?.uploadedAt ? [t("channel.reportAttachment")] : []));
  const dot = (r?: ReportRow) => !r?.uploadedAt ? "var(--dot-red)" : r.balanced ? "var(--dot-green)" : "var(--dot-orange)";

  // Column-aligned "to resolve this month" counts for the strip on top of the table.
  const missingBalance = rows.filter((c) => (rowBy.get(c.id)?.balanceCount ?? 0) === 0).length;
  const missingReport = rows.filter((c) => !rowBy.get(c.id)?.uploadedAt).length;
  const toEmail = rows.filter((c) => rowBy.get(c.id)?.uploadedAt && !rowBy.get(c.id)?.lastSentAt).length;
  const toWa = rows.filter((c) => rowBy.get(c.id)?.uploadedAt && !rowBy.get(c.id)?.lastWhatsappAt).length;

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end", gap: 12 }}>
        <div>
          <div style={{ color: "var(--text-secondary)", fontSize: 12.5 }}>{t("reports.crumb")}</div>
          <h2 style={{ margin: "2px 0 0", fontSize: 21, letterSpacing: "-0.01em" }}>{t("reports.title")}</h2>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 16 }}>
          <HeaderSummary items={[
            { n: missingBalance, label: t("summary.missingBalance") },
            { n: missingReport, label: t("summary.missingReport") },
            { n: toEmail, label: t("summary.toSendEmail") },
            { n: toWa, label: t("summary.toSendWhatsapp") },
          ]} />
          <SyncMonthButton type="TRIAL_BALANCE" period={period} onDone={() => qc.invalidateQueries({ queryKey: ["reports", period] })} />
        </div>
      </div>

      <BulkActionBar count={selected.size} label={t("email.selected", { n: selected.size })}
        onClear={() => setSelected(new Set())}
        onEmail={() => setSendList([...selected].map(target))}
        onWhatsapp={() => setWaBulk([...selected].map((id) => ({ companyId: id, companyName: nameOf(id) })))} />

      <div className="card" style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ minWidth: 900 }}>
          <div style={{ ...gridRow, background: "var(--th-bg)", ...thText }}>
            <div><input type="checkbox" checked={allSelected} disabled={selectableIds.length === 0} onChange={toggleAll} title={t("email.selectAll")} /></div>
            <div />
            <div>{t("documents.company")}</div>
            <div>{t("reports.trialBalance")}</div>
            <div>{t("reports.report")}</div>
            <div>{t("channel.lastEmail")}</div>
            <div>{t("channel.lastWhatsapp")}</div>
            <div style={{ textAlign: "right" }}>{t("channel.actions")}</div>
          </div>

          {rows.map((c) => {
            const r = rowBy.get(c.id);
            const up = !!r?.uploadedAt;
            const manage = () => setManageFor({ id: c.id, name: c.legalName });
            return (
              <div key={c.id} ref={c.id === focusCompany ? focusRef : undefined} style={{ ...gridRow, borderTop: "1px solid var(--hair)", background: (c.id === focusCompany || selected.has(c.id)) ? "var(--row-active)" : undefined, boxShadow: c.id === focusCompany ? "inset 3px 0 0 var(--primary)" : undefined }}>
                <div>{up ? <input type="checkbox" checked={selected.has(c.id)} onChange={() => toggle(c.id)} /> : <span style={{ color: "var(--text-faint)" }}>·</span>}</div>
                <div><span style={{ display: "inline-block", width: 8, height: 8, borderRadius: "50%", background: dot(r) }} title={up ? (r!.balanced ? t("reports.balanced") : t("reports.unbalancedShort")) : t("reports.notUploaded")} /></div>
                <div>
                  <div style={{ fontWeight: 600 }}>{c.legalName}</div>
                  <div className="mono" style={{ color: "var(--text-muted)", fontSize: 11 }}>{c.cui}{c.locality ? ` · ${c.locality}` : ""}</div>
                </div>
                <div>
                  {(r?.balanceCount ?? 0) > 0
                    ? <InfoTip lines={r!.balanceFiles}>
                        <span className="pill round ok">
                          <Icon name="doc" size={10} style={{ verticalAlign: "-1px", marginRight: 4 }} />{r!.balanceCount}
                        </span>
                      </InfoTip>
                    : <span className="pill round danger">{t("reports.missing")}</span>}
                </div>
                <div style={{ display: "flex", gap: 6 }}>
                  <button style={{ ...iconBtn, opacity: up ? 1 : 0.4 }} title={t("reports.download")} disabled={!up} onClick={() => reportsApi.downloadPdf(c.id, period)}><Icon name="download" size={14} /></button>
                  <button style={{ ...iconBtn, opacity: up ? 1 : 0.4 }} title={t("reports.charts")} disabled={!up} onClick={() => setChartsFor({ id: c.id, name: c.legalName })}>📊</button>
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
          {reports.data && rows.length === 0 && <div style={{ padding: 14, color: "var(--text-muted)" }}>{t("taxes.noCompanies")}</div>}
        </div>
      </div>

      {manageFor && <DocumentManagerModal companyId={manageFor.id} companyName={manageFor.name} period={period}
        type="TRIAL_BALANCE" title={t("reports.trialBalance")} accept="application/pdf"
        onClose={() => setManageFor(null)} onChanged={() => qc.invalidateQueries({ queryKey: ["reports", period] })} />}
      {chartsFor && <ReportChartsModal companyId={chartsFor.id} companyName={chartsFor.name} period={period} onClose={() => setChartsFor(null)} />}
      {logFor && <ReportLogModal companyId={logFor.id} companyName={logFor.name} period={period} onClose={() => setLogFor(null)} onCompose={() => setSendList([target(logFor.id)])} />}
      {sendList && <ReportEmailModal targets={sendList} period={period} onClose={() => setSendList(null)} />}
      {waFor && <WhatsAppModal companyId={waFor.id} companyName={waFor.name} kind="REPORT" period={period}
        loadBody={() => waBody(waFor.id)}
        onClose={() => setWaFor(null)} />}
      {waBulk && <WhatsAppBulkModal targets={waBulk} kind="REPORT" period={period} loadBody={waBody}
        onClose={() => setWaBulk(null)} onSent={() => { setSelected(new Set()); qc.invalidateQueries({ queryKey: ["reports", period] }); }} />}
    </div>
  );
}

const gridRow: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "30px 22px minmax(200px,1.4fr) 120px 90px 120px 110px 120px",
  alignItems: "center", gap: 10, padding: "10px 16px",
};
const thText: React.CSSProperties = { fontSize: 9.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "#8a9794" };
const iconBtn: React.CSSProperties = { width: 28, height: 28, display: "grid", placeItems: "center", padding: 0, border: "1px solid var(--border)", borderRadius: 8, background: "var(--surface)", color: "#52605d", cursor: "pointer", fontSize: 13 };
