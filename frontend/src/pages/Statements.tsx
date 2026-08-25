import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { documentsSummaryApi, remindersApi, type CompanyDocSummary } from "../api/documents";
import { reconciliationApi, bankApi } from "../api/bank";
import { emailApi } from "../api/email";
import { usePeriod } from "../lib/period";
import { reminderBody } from "../lib/reminderBody";
import { ActionBtn, WhatsAppAction, RowActions, LastEmailCell, LastWhatsAppCell } from "../components/RowActions";
import { InfoTip } from "../components/InfoTip";
import { SendReminderModal, type ReminderTarget } from "../components/SendReminderModal";
import { ReminderLogModal } from "../components/ReminderLogModal";
import { WhatsAppModal } from "../components/WhatsAppModal";
import { WhatsAppBulkModal, type WhatsAppTarget } from "../components/WhatsAppBulkModal";
import { BulkActionBar } from "../components/BulkActionBar";
import { CompanySearch } from "../components/CompanySearch";
import { loadAllPages } from "../lib/paging";

type DotKind = "green" | "orange" | "red";
const DOT_COLOR: Record<DotKind, string> = { green: "var(--dot-green)", orange: "var(--dot-orange)", red: "var(--dot-red)" };
type Payment = "NONE" | "PARTIAL" | "COMPLETE";

/** Filenames for a tooltip, capped so a company with many invoices doesn't produce a huge box. */
const FILE_TIP_CAP = 25;
const fileLines = (files: string[] | undefined): string[] => {
  const f = files ?? [];
  return f.length <= FILE_TIP_CAP ? f : [...f.slice(0, FILE_TIP_CAP), `+${f.length - FILE_TIP_CAP} …`];
};

/** Row health dot = payment/matching of the company's invoices/receipts for the period. */
function rowStatus(s: CompanyDocSummary | undefined, payment: Payment): { kind: DotKind; key: string } {
  const inv = s?.invoiceReceiptCount ?? 0;
  const hasBank = s?.hasBankStatement ?? false;
  if (inv === 0) return { kind: "red", key: "statements.dot.nothing" };
  if (payment === "COMPLETE") return { kind: "green", key: "statements.dot.complete" };
  if (payment === "PARTIAL") return { kind: "orange", key: "statements.dot.partial" };
  return hasBank ? { kind: "red", key: "statements.dot.unmatched" } : { kind: "orange", key: "statements.dot.waiting" };
}

/** Display-only status pill (no link — actions live in the Actions column). */
function StatusPill({ label, kind, title }:
  { label: React.ReactNode; kind: "ok" | "danger" | "muted" | "warn"; title: string }) {
  return <span className={`pill round ${kind}`} title={title} style={{ marginRight: 4 }}>{label}</span>;
}

/** Statements & invoices — monthly hub list, Console (B) skin. */
export function Statements() {
  const { t } = useTranslation();
  const { period } = usePeriod();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const goReconcile = (id: string) => navigate(`/statements/${id}/reconcile`);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [sendList, setSendList] = useState<ReminderTarget[] | null>(null);
  const [logFor, setLogFor] = useState<{ id: string; name: string } | null>(null);
  const [waFor, setWaFor] = useState<{ id: string; name: string } | null>(null);
  const [waBulk, setWaBulk] = useState<WhatsAppTarget[] | null>(null);

  const [dq, setDq] = useState("");
  const companies = useInfiniteQuery({
    queryKey: ["companies-page", dq],
    queryFn: ({ pageParam }) => companiesApi.listPage(dq, pageParam, 25),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.last ? undefined : last.page + 1),
  });
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

  const rows = companies.data?.pages.flatMap((p) => p.content) ?? [];
  const selectableIds = rows.filter((c) => needsReminder(c.id)).map((c) => c.id);
  const allSelected = selectableIds.length > 0 && selectableIds.every((id) => selected.has(id));

  useEffect(() => { setSelected(new Set()); }, [period, dq]);

  // Auto-load the next page when the sentinel scrolls near the viewport.
  const sentinel = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const node = sentinel.current;
    if (!node || !companies.hasNextPage) return;
    const observer = new IntersectionObserver(
      (entries) => { if (entries[0].isIntersecting && !companies.isFetchingNextPage) void companies.fetchNextPage(); },
      { rootMargin: "250px" });
    observer.observe(node);
    return () => observer.disconnect();
  }, [companies.hasNextPage, companies.isFetchingNextPage, companies.fetchNextPage]);

  const toggle = (id: string) => setSelected((p) => { const n = new Set(p); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  // Select-all covers EVERY matching company (loads all pages), not just those scrolled into view.
  const toggleAll = async () => {
    if (allSelected) { setSelected(new Set()); return; }
    const all = await loadAllPages(companies);
    setSelected(new Set(all.filter((c) => needsReminder(c.id)).map((c) => c.id)));
  };
  const nameOf = (id: string) => rows.find((c) => c.id === id)?.legalName ?? id;
  const target = (id: string): ReminderTarget => {
    const s = byCompany.get(id);
    return { id, name: nameOf(id), hasBankStatement: s?.hasBankStatement ?? false, hasInvoiceOrReceipt: s?.hasInvoiceOrReceipt ?? false };
  };
  const waBody = async (id: string) => {
    const env = await emailApi.envelope(id).catch(() => null);
    const hasBank = byCompany.get(id)?.hasBankStatement ?? false;
    const txns = hasBank ? await bankApi.transactions(id, period) : [];
    const missing = txns.filter((tx) => tx.requiresDocument && !tx.matched);
    return reminderBody(t, period.slice(0, 7), hasBank, missing, env?.fromName ?? null);
  };

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end" }}>
        <div>
          <div style={{ color: "var(--text-secondary)", fontSize: 12.5 }}>{t("statements.crumb")}</div>
          <h2 style={{ margin: "2px 0 0", fontSize: 21, letterSpacing: "-0.01em" }}>{t("documents.title")}</h2>
        </div>
        <CompanySearch onSearch={setDq} />
      </div>

      <BulkActionBar count={selected.size} label={t("email.selected", { n: selected.size })}
        onClear={() => setSelected(new Set())}
        onEmail={() => setSendList([...selected].map(target))}
        onWhatsapp={() => setWaBulk([...selected].map((id) => ({ companyId: id, companyName: nameOf(id) })))} />

      <div className="card" style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ minWidth: 1040 }}>
          <div style={{ ...gridRow, background: "var(--th-bg)", ...thText }}>
            <div><input type="checkbox" checked={allSelected} disabled={selectableIds.length === 0} onChange={toggleAll} title={t("email.selectAll")} /></div>
            <div />
            <div>{t("documents.company")}</div>
            <div>{t("statements.bankStatement")}</div>
            <div>{t("statements.invoices")}</div>
            <div>{t("statements.reconciliation")}</div>
            <div>{t("channel.lastEmail")}</div>
            <div>{t("channel.lastWhatsapp")}</div>
            <div style={{ textAlign: "right" }}>{t("channel.actions")}</div>
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
            const r = reminderBy.get(c.id);
            return (
              <div key={c.id} style={{ ...gridRow, borderTop: "1px solid var(--hair)", background: selected.has(c.id) ? "var(--row-active)" : undefined }}>
                <div>{selectable ? <input type="checkbox" checked={selected.has(c.id)} onChange={() => toggle(c.id)} /> : <span style={{ color: "var(--text-faint)" }}>·</span>}</div>
                <div><span role="img" aria-label={t(st.key)} title={t(st.key)} style={{ display: "inline-block", width: 8, height: 8, borderRadius: "50%", background: DOT_COLOR[st.kind] }} /></div>
                <div>
                  <div style={{ fontWeight: 600 }}>{c.legalName}</div>
                  <div className="mono" style={{ color: "var(--text-muted)", fontSize: 11 }}>{c.cui}{c.locality ? ` · ${c.locality}` : ""}</div>
                  {((s?.misfiledCount ?? 0) > 0 || (s?.duplicateCount ?? 0) > 0) && (
                    <div style={{ marginTop: 3, display: "flex", gap: 4, flexWrap: "wrap" }}>
                      {(s?.misfiledCount ?? 0) > 0 && (
                        <InfoTip lines={s?.misfiledFiles ?? []}>
                          <span className="pill round warn" title={t("statements.misfiled.tip")}>
                            ⚠ {t("statements.misfiled", { n: s?.misfiledCount ?? 0 })}
                          </span>
                        </InfoTip>
                      )}
                      {(s?.duplicateCount ?? 0) > 0 && (
                        <InfoTip lines={s?.duplicateFiles ?? []}>
                          <span className="pill round danger" title={t("statements.duplicate.tip")}>DUP</span>
                        </InfoTip>
                      )}
                    </div>
                  )}
                </div>
                <div>
                  {hasBank
                    ? <InfoTip lines={fileLines(s?.bankStatementFiles)}>
                        <StatusPill kind="ok" label={s?.bankStatementCount ?? 0} title={t("statements.chip.statements")} />
                      </InfoTip>
                    : <StatusPill kind="danger" label={t("statements.missing")} title={t("statements.chip.noStatement")} />}
                </div>
                <div>
                  {present > 0
                    ? <InfoTip lines={fileLines(s?.invoiceReceiptFiles)}>
                        <StatusPill kind="ok" label={present} title={t("statements.chip.present")} />
                      </InfoTip>
                    : <span style={{ color: "var(--text-faint)" }}>—</span>}
                </div>
                <div>
                  {!hasBank
                    ? <span className="pill round muted" title={t("statements.cpl.naTip")}>{t("statements.cpl.na")}</span>
                    : cpl === "COMPLETE"
                      ? <span className="pill round ok">{t("statements.cpl.complete")}</span>
                      : missing > 0 || noMatch > 0
                        ? <>
                            {missing > 0 && <StatusPill kind="danger" label={missing} title={t("statements.chip.missing")} />}
                            {noMatch > 0 && <StatusPill kind="muted" label={noMatch} title={t("statements.chip.noMatch")} />}
                          </>
                        : cpl === "PARTIAL"
                          ? <span className="pill round warn">{t("statements.cpl.partial")}</span>
                          : <span className="pill round muted">{t("statements.cpl.notStarted")}</span>}
                </div>
                <div><LastEmailCell lastSentAt={r?.lastSentAt} count={r?.count} onOpen={() => setLogFor({ id: c.id, name: c.legalName })} /></div>
                <div><LastWhatsAppCell lastSentAt={r?.lastWhatsappAt} count={r?.whatsappCount} onOpen={() => setWaFor({ id: c.id, name: c.legalName })} /></div>
                <div>
                  <RowActions>
                    <ActionBtn icon="reconcile" title={t("channel.reconcile")} onClick={() => goReconcile(c.id)} />
                    <ActionBtn icon="mail" title={t("channel.email")} onClick={() => setSendList([target(c.id)])} />
                    <WhatsAppAction onClick={() => setWaFor({ id: c.id, name: c.legalName })} />
                  </RowActions>
                </div>
              </div>
            );
          })}
          {!companies.isLoading && rows.length === 0 && <div style={{ padding: 14, color: "var(--text-muted)" }}>{t("taxes.noCompanies")}</div>}
          <div ref={sentinel} style={{ height: 1 }} />
          {companies.isFetchingNextPage && <div style={{ padding: 10, textAlign: "center", color: "var(--text-muted)", fontSize: 12 }}>{t("common.loading")}</div>}
        </div>
      </div>

      {sendList && <SendReminderModal companies={sendList} period={period} onClose={() => setSendList(null)} />}
      {waFor && <WhatsAppModal companyId={waFor.id} companyName={waFor.name} kind="DOCUMENT_REMINDER" period={period}
        loadBody={() => waBody(waFor.id)}
        onClose={() => setWaFor(null)} />}
      {waBulk && <WhatsAppBulkModal targets={waBulk} kind="DOCUMENT_REMINDER" period={period} loadBody={waBody}
        onClose={() => setWaBulk(null)} onSent={() => { setSelected(new Set()); qc.invalidateQueries({ queryKey: ["doc-reminders", period] }); }} />}
      {logFor && <ReminderLogModal companyId={logFor.id} companyName={logFor.name} period={period}
        onClose={() => setLogFor(null)}
        onCompose={() => setSendList([target(logFor.id)])} />}
    </div>
  );
}

const gridRow: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "30px 24px minmax(200px,1.4fr) 84px 96px 150px 120px 110px 120px",
  alignItems: "center", gap: 10, padding: "10px 16px",
};
const thText: React.CSSProperties = { fontSize: 9.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "#8a9794" };
