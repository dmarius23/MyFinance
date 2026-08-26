import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { reportsApi, type ReportListRow } from "../api/reports";
import type { CompletenessFilter as FilterValue } from "../api/payroll";
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
import { SyncMonthButton } from "../components/SyncMonthButton";
import { CompanySearch } from "../components/CompanySearch";
import { CompletenessFilter } from "../components/CompletenessFilter";
import { loadAllPages } from "../lib/paging";
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

  const [dq, setDq] = useState("");
  const [filter, setFilter] = useState<FilterValue>("all");
  const pageQ = useInfiniteQuery({
    queryKey: ["reports-page", period, dq, filter],
    queryFn: ({ pageParam }) => reportsApi.listPage(period, dq, filter, pageParam, 25),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.last ? undefined : last.page + 1),
  });
  const invalidateList = () => qc.invalidateQueries({ queryKey: ["reports-page", period] });

  const rows: ReportListRow[] = pageQ.data?.pages.flatMap((p) => p.content) ?? [];
  const selectableIds = rows.filter((r) => !!r.uploadedAt).map((r) => r.companyId);
  const allSelected = selectableIds.length > 0 && selectableIds.every((id) => selected.has(id));

  useEffect(() => { setSelected(new Set()); }, [period, dq, filter]);

  // Auto-load the next page when the sentinel scrolls near the viewport.
  const sentinel = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const node = sentinel.current;
    if (!node || !pageQ.hasNextPage) return;
    const observer = new IntersectionObserver(
      (entries) => { if (entries[0].isIntersecting && !pageQ.isFetchingNextPage) void pageQ.fetchNextPage(); },
      { rootMargin: "250px" });
    observer.observe(node);
    return () => observer.disconnect();
  }, [pageQ.hasNextPage, pageQ.isFetchingNextPage, pageQ.fetchNextPage]);

  // Deep-link from the dashboard (?company=&open=1): open that company's document manager once loaded.
  const autoOpened = useRef(false);
  useEffect(() => {
    if (!openModal || autoOpened.current) return;
    const r = rows.find((x) => x.companyId === focusCompany);
    if (r) { autoOpened.current = true; setManageFor({ id: r.companyId, name: r.companyName }); }
  }, [openModal, focusCompany, rows]);

  const toggle = (id: string) => setSelected((p) => { const n = new Set(p); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  // Select-all covers EVERY matching company (loads all pages), not just those scrolled into view.
  const toggleAll = async () => {
    if (allSelected) { setSelected(new Set()); return; }
    const all = await loadAllPages(pageQ);
    setSelected(new Set(all.filter((r) => !!r.uploadedAt).map((r) => r.companyId)));
  };
  const rowById = new Map(rows.map((r) => [r.companyId, r]));
  const nameOf = (id: string) => rowById.get(id)?.companyName ?? id;
  const target = (id: string): ReportTarget => ({ companyId: id, companyName: nameOf(id) });
  const waBody = (id: string) => reportsApi.emailBody(id, period).then((r) =>
    r.body + attachmentsNote(t("channel.attachments"), rowById.get(id)?.uploadedAt ? [t("channel.reportAttachment")] : []));
  const dot = (r: ReportListRow) => !r.uploadedAt ? "var(--dot-red)" : r.balanced ? "var(--dot-green)" : "var(--dot-orange)";

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end", gap: 12 }}>
        <div>
          <div style={{ color: "var(--text-secondary)", fontSize: 12.5 }}>{t("reports.crumb")}</div>
          <h2 style={{ margin: "2px 0 0", fontSize: 21, letterSpacing: "-0.01em" }}>{t("reports.title")}</h2>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <CompletenessFilter value={filter} onChange={setFilter} />
          <CompanySearch onSearch={setDq} />
          <SyncMonthButton type="TRIAL_BALANCE" period={period} onDone={invalidateList} />
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

          {rows.map((r) => {
            const up = !!r.uploadedAt;
            const manage = () => setManageFor({ id: r.companyId, name: r.companyName });
            return (
              <div key={r.companyId} ref={r.companyId === focusCompany ? focusRef : undefined} style={{ ...gridRow, borderTop: "1px solid var(--hair)", background: (r.companyId === focusCompany || selected.has(r.companyId)) ? "var(--row-active)" : undefined, boxShadow: r.companyId === focusCompany ? "inset 3px 0 0 var(--primary)" : undefined }}>
                <div>{up ? <input type="checkbox" checked={selected.has(r.companyId)} onChange={() => toggle(r.companyId)} /> : <span style={{ color: "var(--text-faint)" }}>·</span>}</div>
                <div><span style={{ display: "inline-block", width: 8, height: 8, borderRadius: "50%", background: dot(r) }} title={up ? (r.balanced ? t("reports.balanced") : t("reports.unbalancedShort")) : t("reports.notUploaded")} /></div>
                <div>
                  <div style={{ fontWeight: 600 }}>{r.companyName}</div>
                  <div className="mono" style={{ color: "var(--text-muted)", fontSize: 11 }}>{r.cui}{r.locality ? ` · ${r.locality}` : ""}</div>
                </div>
                <div>
                  {(r.balanceCount ?? 0) > 0
                    ? <InfoTip lines={r.balanceFiles}>
                        <span className="pill round ok">
                          <Icon name="doc" size={10} style={{ verticalAlign: "-1px", marginRight: 4 }} />{r.balanceCount}
                        </span>
                      </InfoTip>
                    : <span className="pill round danger">{t("reports.missing")}</span>}
                </div>
                <div style={{ display: "flex", gap: 6 }}>
                  <button style={{ ...iconBtn, opacity: up ? 1 : 0.4 }} title={t("reports.download")} disabled={!up} onClick={() => reportsApi.downloadPdf(r.companyId, period)}><Icon name="download" size={14} /></button>
                  <button style={{ ...iconBtn, opacity: up ? 1 : 0.4 }} title={t("reports.charts")} disabled={!up} onClick={() => setChartsFor({ id: r.companyId, name: r.companyName })}>📊</button>
                </div>
                <div><LastEmailCell lastSentAt={r.lastSentAt} count={r.sentCount} onOpen={() => setLogFor({ id: r.companyId, name: r.companyName })} /></div>
                <div><LastWhatsAppCell lastSentAt={r.lastWhatsappAt} count={r.whatsappCount} onOpen={() => setWaFor({ id: r.companyId, name: r.companyName })} /></div>
                <div>
                  <RowActions>
                    <ActionBtn icon="upload" title={t("channel.upload")} onClick={manage} />
                    <ActionBtn icon="mail" title={t("channel.email")} onClick={() => setSendList([target(r.companyId)])} />
                    <WhatsAppAction onClick={() => setWaFor({ id: r.companyId, name: r.companyName })} />
                  </RowActions>
                </div>
              </div>
            );
          })}
          {!pageQ.isLoading && rows.length === 0 && <div style={{ padding: 14, color: "var(--text-muted)" }}>{filter === "missing" ? t("filter.noneMissing") : t("taxes.noCompanies")}</div>}
          <div ref={sentinel} style={{ height: 1 }} />
          {pageQ.isFetchingNextPage && <div style={{ padding: 10, textAlign: "center", color: "var(--text-muted)", fontSize: 12 }}>{t("common.loading")}</div>}
        </div>
      </div>

      {manageFor && <DocumentManagerModal companyId={manageFor.id} companyName={manageFor.name} period={period}
        type="TRIAL_BALANCE" title={t("reports.trialBalance")} accept="application/pdf"
        onClose={() => setManageFor(null)} onChanged={invalidateList} />}
      {chartsFor && <ReportChartsModal companyId={chartsFor.id} companyName={chartsFor.name} period={period} onClose={() => setChartsFor(null)} />}
      {logFor && <ReportLogModal companyId={logFor.id} companyName={logFor.name} period={period} onClose={() => setLogFor(null)} onCompose={() => setSendList([target(logFor.id)])} />}
      {sendList && <ReportEmailModal targets={sendList} period={period} onClose={() => setSendList(null)} />}
      {waFor && <WhatsAppModal companyId={waFor.id} companyName={waFor.name} kind="REPORT" period={period}
        loadBody={() => waBody(waFor.id)}
        onClose={() => setWaFor(null)} />}
      {waBulk && <WhatsAppBulkModal targets={waBulk} kind="REPORT" period={period} loadBody={waBody}
        onClose={() => setWaBulk(null)} onSent={() => { setSelected(new Set()); invalidateList(); }} />}
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
