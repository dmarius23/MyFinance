import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { taxPaymentsApi, DECLARATION_TYPES, type TaxPaymentRow, type DeclarationCell, type DeclType } from "../api/taxes";
import type { CompletenessFilter as FilterValue } from "../api/payroll";
import { CompletenessFilter } from "../components/CompletenessFilter";
import { ApiError } from "../lib/apiClient";
import { usePeriod } from "../lib/period";
import { useCompanyFocus } from "../lib/useCompanyFocus";
import { ActionBtn, WhatsAppAction, RowActions, LastEmailCell, LastWhatsAppCell } from "../components/RowActions";
import { InfoTip } from "../components/InfoTip";
import { TaxPaymentModal } from "../components/TaxPaymentModal";
import { DeclarationsModal } from "../components/DeclarationsModal";
import { EmailPreviewModal, type PreviewTarget } from "../components/EmailPreviewModal";
import { NotificationLogModal } from "../components/NotificationLogModal";
import { WhatsAppModal } from "../components/WhatsAppModal";
import { WhatsAppBulkModal, type WhatsAppTarget } from "../components/WhatsAppBulkModal";
import { BulkActionBar } from "../components/BulkActionBar";
import { SyncMonthButton } from "../components/SyncMonthButton";
import { CompanySearch } from "../components/CompanySearch";
import { loadAllPages } from "../lib/paging";

const money = (n: number) => n.toLocaleString("ro-RO", { minimumFractionDigits: 0 });

const cellsFor = (row: TaxPaymentRow, type: string) => row.declarations.filter((d) => d.type === type);
const otherCells = (row: TaxPaymentRow) =>
  row.declarations.filter((d) => !(DECLARATION_TYPES as readonly string[]).includes(d.type));
const toPay = (row: TaxPaymentRow) => row.declarations.reduce((s, d) => s + d.amount, 0);

/** Build the breakdown lines (one per obligation, "code - label: amount RON") + a total when >1. */
function breakdownLines(cells: DeclarationCell[], totalLabel: string): string[] {
  const lines = cells.map((c) => {
    const code = [c.cod, c.label].filter(Boolean).join(" - ");
    return code ? `${code}: ${money(c.amount)} RON` : `${money(c.amount)} RON`;
  });
  if (lines.length > 1) {
    const total = cells.reduce((s, c) => s + c.amount, 0);
    lines.push(`${totalLabel}: ${money(total)} RON`);
  }
  return lines;
}

/**
 * A declaration status cell — display-only and compact: shows just the type TOTAL (sum of the company's
 * obligations of that type) on one line. Hovering reveals the breakdown — one line per obligation
 * ("code - label: amount RON") plus the total. Empty renders the {@code missingLabel} chip when given
 * (a known declaration column), else a muted dash.
 */
function DeclStack({ cells, missingLabel, naLabel }: { cells: DeclarationCell[]; missingLabel?: string; naLabel?: string }) {
  const { t } = useTranslation();
  if (cells.length === 0) {
    // Not expected for this company (e.g. D300 for a non-VAT payer, D112 with no employees) → muted N/A,
    // never a "missing" alert. Expected-but-absent → the danger "lipsă" chip. Neither → a muted dash.
    if (naLabel) return <span className="pill round muted">{naLabel}</span>;
    return missingLabel
      ? <span className="pill round danger">{missingLabel}</span>
      : <span style={{ color: "var(--text-faint)" }}>—</span>;
  }
  const total = cells.reduce((s, c) => s + c.amount, 0);
  const anyMismatch = cells.some((c) => c.mismatch);
  return (
    <InfoTip lines={breakdownLines(cells, t("taxes.total"))} emphasizeLast>
      <span className="mono">
        {money(total)} RON{anyMismatch && <span style={{ color: "#b45309", marginLeft: 4 }}>⚠</span>}
      </span>
    </InfoTip>
  );
}

/** MOD-07 — Taxes & payments monthly list, Console (B) skin. */
export function TaxPayments() {
  const { t, i18n } = useTranslation();
  const qc = useQueryClient();
  const { period } = usePeriod();
  const { focusCompany, focusRef, openModal } = useCompanyFocus();
  const [emailFor, setEmailFor] = useState<{ id: string; name: string } | null>(null);
  const [declFor, setDeclFor] = useState<{ id: string; name: string } | null>(null);
  const [logFor, setLogFor] = useState<{ id: string; name: string } | null>(null);
  const [waFor, setWaFor] = useState<{ id: string; name: string } | null>(null);
  const [waBulk, setWaBulk] = useState<WhatsAppTarget[] | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkTargets, setBulkTargets] = useState<PreviewTarget[] | null>(null);

  const [dq, setDq] = useState("");
  const [filter, setFilter] = useState<FilterValue>("all");
  const [declType, setDeclType] = useState<DeclType | null>(null);
  const { data, isLoading, error, fetchNextPage, hasNextPage, isFetchingNextPage } = useInfiniteQuery({
    queryKey: ["tax-list-page", period, dq, filter, declType],
    queryFn: ({ pageParam }) => taxPaymentsApi.listPage(period, dq, filter, filter === "missing" ? declType : null, pageParam as number),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.last ? undefined : last.page + 1),
  });
  const rows = data?.pages.flatMap((p) => p.content) ?? [];

  useEffect(() => { setSelected(new Set()); }, [period, dq, filter, declType]);

  // Auto-load the next page when the sentinel scrolls near the viewport.
  const sentinel = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const node = sentinel.current;
    if (!node || !hasNextPage) return;
    const observer = new IntersectionObserver(
      (entries) => { if (entries[0].isIntersecting && !isFetchingNextPage) void fetchNextPage(); },
      { rootMargin: "250px" });
    observer.observe(node);
    return () => observer.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

  // Deep-link from the dashboard (?company=&open=1): open that company's declarations if it's loaded.
  const autoOpened = useRef(false);
  useEffect(() => {
    if (!openModal || autoOpened.current) return;
    const row = (data?.pages.flatMap((p) => p.content) ?? []).find((r) => r.companyId === focusCompany);
    if (row) { autoOpened.current = true; setDeclFor({ id: row.companyId, name: row.companyName }); }
  }, [openModal, focusCompany, data]);

  const monthLabel = new Date(period).toLocaleDateString(i18n.language === "ro" ? "ro-RO" : "en-US", { month: "long", year: "numeric" });

  const selectable = rows.filter((r) => r.declarations.length > 0).map((r) => r.companyId);
  const allSelected = selectable.length > 0 && selectable.every((id) => selected.has(id));
  const toggle = (id: string) => setSelected((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  // Select-all covers EVERY matching company (loads all pages), not just the ones scrolled into view.
  const toggleAll = async () => {
    if (allSelected) { setSelected(new Set()); return; }
    const all = await loadAllPages({ data, hasNextPage, fetchNextPage });
    setSelected(new Set(all.filter((r) => r.declarations.length > 0).map((r) => r.companyId)));
  };

  const refreshList = () => void qc.invalidateQueries({ queryKey: ["tax-list-page", period] });
  const openBulk = () => {
    const targets: PreviewTarget[] = rows.filter((r) => selected.has(r.companyId)).map((r) => ({
      companyId: r.companyId, companyName: r.companyName,
      declarationIds: [...new Set(r.declarations.map((d) => d.declarationId))],
    }));
    if (targets.length) setBulkTargets(targets);
  };
  const waBody = (id: string) => taxPaymentsApi.previewEmail(id,
    [...new Set((rows.find((r) => r.companyId === id)?.declarations ?? []).map((d) => d.declarationId))])
    .then((r) => r.body ?? "");
  const openWaBulk = () =>
    setWaBulk(rows.filter((r) => selected.has(r.companyId)).map((r) => ({ companyId: r.companyId, companyName: r.companyName })));

  return (
    <div style={{ display: "grid", gap: 16 }}>
      {/* Header */}
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end" }}>
        <div>
          <div style={{ color: "var(--text-secondary)", fontSize: 12.5 }}>{t("taxes.subtitle")} · {monthLabel}</div>
          <h2 style={{ margin: "2px 0 0", fontSize: 21, letterSpacing: "-0.01em" }}>{t("taxes.title")}</h2>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          {/* Declaration-type picker sits BEFORE the toggle and is always rendered (disabled unless
              "needs attention" is active) so switching the filter never reflows the row. */}
          <select value={declType ?? ""} disabled={filter !== "missing"}
            onChange={(e) => setDeclType((e.target.value || null) as DeclType | null)}
            title={t("taxes.filterByType")}
            style={{ fontSize: 12, padding: "5px 8px", borderRadius: 8, border: "1px solid var(--border)",
              background: "var(--surface)", color: "var(--text-secondary)",
              opacity: filter === "missing" ? 1 : 0.45, cursor: filter === "missing" ? "pointer" : "default" }}>
            <option value="">{t("filter.all")}</option>
            {DECLARATION_TYPES.map((ty) => <option key={ty} value={ty}>{ty}</option>)}
          </select>
          <CompletenessFilter value={filter} onChange={setFilter} />
          <CompanySearch onSearch={setDq} />
          <SyncMonthButton type="DECLARATION" period={period} onDone={refreshList} />
        </div>
      </div>

      {/* Floating bulk bar */}
      <BulkActionBar count={selected.size} label={t("taxes.companiesSelected")}
        onClear={() => setSelected(new Set())} onEmail={openBulk} onWhatsapp={openWaBulk} />

      {/* List */}
      <div className="card" style={{ padding: 0, overflow: "hidden" }}>
        {isLoading && <p style={{ padding: 14 }}>{t("common.loading")}</p>}
        {error && <p style={{ padding: 14, color: "var(--danger-fg)" }}>{error instanceof ApiError ? error.message : "Failed to load"}</p>}

        <div style={{ minWidth: 1120 }}>
          <div style={{ ...gridRow, background: "var(--th-bg)", ...thText }}>
            <div><input type="checkbox" checked={allSelected} disabled={selectable.length === 0} onChange={toggleAll} /></div>
            <div>{t("documents.company")}</div>
            {DECLARATION_TYPES.map((ty) => <div key={ty} style={{ textAlign: "right" }}>{ty}</div>)}
            <div style={{ textAlign: "right" }}>{t("taxes.otherType")}</div>
            <div style={{ textAlign: "right" }}>{t("taxes.toPayCol")}</div>
            <div>{t("channel.lastEmail")}</div>
            <div>{t("channel.lastWhatsapp")}</div>
            <div style={{ textAlign: "right" }}>{t("channel.actions")}</div>
          </div>

          {rows.map((row) => {
            const total = toPay(row);
            const has = row.declarations.length > 0;
            return (
              <div key={row.companyId} ref={row.companyId === focusCompany ? focusRef : undefined} style={{ ...gridRow, borderTop: "1px solid var(--hair)", background: (row.companyId === focusCompany || selected.has(row.companyId)) ? "var(--row-active)" : undefined, boxShadow: row.companyId === focusCompany ? "inset 3px 0 0 var(--primary)" : undefined }}>
                <div>{has ? <input type="checkbox" checked={selected.has(row.companyId)} onChange={() => toggle(row.companyId)} /> : <span style={{ color: "var(--text-faint)" }}>·</span>}</div>
                <div>
                  <div style={{ fontWeight: 600 }}>{row.companyName}</div>
                  <div className="mono" style={{ color: "var(--text-muted)", fontSize: 11 }}>{row.cui}{row.residence ? ` · ${row.residence}` : ""}</div>
                </div>
                {DECLARATION_TYPES.map((ty) => {
                  // D100 owed by all; D112 only with employees; D300 only for VAT payers.
                  const expected = ty === "D112" ? row.owesD112 : ty === "D300" ? row.owesD300 : true;
                  return (
                    <div key={ty} className="mono" style={{ textAlign: "right", fontSize: 12.5 }}>
                      <DeclStack cells={cellsFor(row, ty)}
                        missingLabel={expected ? t("taxes.missing") : undefined}
                        naLabel={expected ? undefined : t("taxes.na")} />
                    </div>
                  );
                })}
                <div className="mono" style={{ textAlign: "right", fontSize: 12.5 }}>
                  <DeclStack cells={otherCells(row)} />
                </div>
                <div className="mono" style={{ textAlign: "right", fontWeight: 700, fontSize: 13 }}>
                  {total > 0
                    ? <InfoTip lines={breakdownLines(row.declarations, t("taxes.total"))} emphasizeLast><span>{money(total)} RON</span></InfoTip>
                    : <span style={{ color: "var(--text-faint)", fontWeight: 400 }}>—</span>}
                </div>
                <div><LastEmailCell lastSentAt={row.lastEmailAt} count={row.emailCount} onOpen={() => setLogFor({ id: row.companyId, name: row.companyName })} /></div>
                <div><LastWhatsAppCell lastSentAt={row.lastWhatsappAt} count={row.whatsappCount} onOpen={() => setWaFor({ id: row.companyId, name: row.companyName })} /></div>
                <div>
                  <RowActions>
                    <ActionBtn icon="upload" title={t("channel.upload")} onClick={() => setDeclFor({ id: row.companyId, name: row.companyName })} />
                    <ActionBtn icon="mail" title={t("channel.email")} onClick={() => setEmailFor({ id: row.companyId, name: row.companyName })} />
                    <WhatsAppAction onClick={() => setWaFor({ id: row.companyId, name: row.companyName })} />
                  </RowActions>
                </div>
              </div>
            );
          })}
          {!isLoading && rows.length === 0 && <div style={{ padding: 14, color: "var(--text-muted)" }}>{t("taxes.noCompanies")}</div>}
          <div ref={sentinel} style={{ height: 1 }} />
          {isFetchingNextPage && <div style={{ padding: 10, textAlign: "center", color: "var(--text-muted)", fontSize: 12 }}>{t("common.loading")}</div>}
        </div>
      </div>

      {declFor && <DeclarationsModal companyId={declFor.id} companyName={declFor.name} period={period} onClose={() => { setDeclFor(null); refreshList(); }} />}
      {emailFor && <TaxPaymentModal companyId={emailFor.id} companyName={emailFor.name} period={period} onClose={() => { setEmailFor(null); refreshList(); }} />}
      {logFor && <NotificationLogModal companyId={logFor.id} companyName={logFor.name} period={period}
        onClose={() => { setLogFor(null); refreshList(); }}
        onCompose={() => setEmailFor({ id: logFor.id, name: logFor.name })} />}
      {waFor && <WhatsAppModal companyId={waFor.id} companyName={waFor.name} kind="TAX" period={period}
        loadBody={() => waBody(waFor.id)}
        onClose={() => setWaFor(null)} />}
      {waBulk && <WhatsAppBulkModal targets={waBulk} kind="TAX" period={period} loadBody={waBody}
        onClose={() => setWaBulk(null)} onSent={() => { setSelected(new Set()); refreshList(); }} />}
      {bulkTargets && <EmailPreviewModal targets={bulkTargets} period={period}
        onClose={() => setBulkTargets(null)} onSent={() => { setSelected(new Set()); refreshList(); }} />}
    </div>
  );
}

const gridRow: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "30px minmax(200px,1.5fr) 96px 96px 96px 96px 100px 120px 110px 120px",
  alignItems: "center", gap: 10, padding: "10px 16px",
};
const thText: React.CSSProperties = { fontSize: 9.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "#8a9794" };
