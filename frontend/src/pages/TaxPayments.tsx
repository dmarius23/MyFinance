import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { taxPaymentsApi, DECLARATION_TYPES, type TaxPaymentRow, type DeclarationCell } from "../api/taxes";
import { ApiError } from "../lib/apiClient";
import { usePeriod } from "../lib/period";
import { useCompanyFocus } from "../lib/useCompanyFocus";
import { Icon } from "../components/Icon";
import { ActionBtn, WhatsAppAction, RowActions, LastEmailCell, LastWhatsAppCell } from "../components/RowActions";
import { TaxPaymentModal } from "../components/TaxPaymentModal";
import { DeclarationsModal } from "../components/DeclarationsModal";
import { EmailPreviewModal, type PreviewTarget } from "../components/EmailPreviewModal";
import { NotificationLogModal } from "../components/NotificationLogModal";

const money = (n: number) => n.toLocaleString("ro-RO", { minimumFractionDigits: 0 });

const cellsFor = (row: TaxPaymentRow, type: string) => row.declarations.filter((d) => d.type === type);
const otherCells = (row: TaxPaymentRow) =>
  row.declarations.filter((d) => !(DECLARATION_TYPES as readonly string[]).includes(d.type));
const toPay = (row: TaxPaymentRow) => row.declarations.reduce((s, d) => s + d.amount, 0);

/**
 * A lightweight hover tooltip: shows {@code lines} in a styled box that follows the cursor. Uses
 * position:fixed (from the pointer's viewport coords) so it is never clipped by the table's horizontal
 * scroll container, and appears instantly (unlike the native title attribute). The last line is
 * emphasised as a total when more than one line is given.
 */
function InfoTip({ lines, children }: { lines: string[]; children: React.ReactNode }) {
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null);
  return (
    <span
      style={{ cursor: "help" }}
      onMouseEnter={(e) => setPos({ x: e.clientX, y: e.clientY })}
      onMouseMove={(e) => setPos({ x: e.clientX, y: e.clientY })}
      onMouseLeave={() => setPos(null)}
    >
      {children}
      {pos && (
        <div
          role="tooltip"
          style={{
            position: "fixed",
            left: Math.min(pos.x + 14, window.innerWidth - 260),
            top: pos.y + 16,
            zIndex: 1000,
            background: "var(--chrome-bg, #1f2a37)",
            color: "var(--chrome-text, #f3f8f7)",
            padding: "8px 11px",
            borderRadius: 8,
            fontSize: 12,
            lineHeight: 1.55,
            whiteSpace: "nowrap",
            boxShadow: "0 8px 26px rgba(0,0,0,0.30)",
            pointerEvents: "none",
            display: "grid",
            gap: 1,
          }}
        >
          {lines.map((l, i) => {
            const isTotal = lines.length > 1 && i === lines.length - 1;
            return (
              <div key={i} style={isTotal
                ? { borderTop: "1px solid rgba(255,255,255,0.18)", marginTop: 3, paddingTop: 4, fontWeight: 700 }
                : undefined}>{l}</div>
            );
          })}
        </div>
      )}
    </span>
  );
}

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
function DeclStack({ cells, missingLabel }: { cells: DeclarationCell[]; missingLabel?: string }) {
  const { t } = useTranslation();
  if (cells.length === 0) {
    return missingLabel
      ? <span className="pill round danger">{missingLabel}</span>
      : <span style={{ color: "var(--text-faint)" }}>—</span>;
  }
  const total = cells.reduce((s, c) => s + c.amount, 0);
  const anyMismatch = cells.some((c) => c.mismatch);
  return (
    <InfoTip lines={breakdownLines(cells, t("taxes.total"))}>
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
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkTargets, setBulkTargets] = useState<PreviewTarget[] | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ["tax-list", period],
    queryFn: () => taxPaymentsApi.list(period),
  });
  const rows = data ?? [];

  useEffect(() => { setSelected(new Set()); }, [period]);

  // Deep-link from the dashboard (?company=&open=1): open that company's declarations manager once loaded.
  const autoOpened = useRef(false);
  useEffect(() => {
    if (!openModal || autoOpened.current) return;
    const row = (data ?? []).find((r) => r.companyId === focusCompany);
    if (row) { autoOpened.current = true; setDeclFor({ id: row.companyId, name: row.companyName }); }
  }, [openModal, focusCompany, data]);

  const monthLabel = new Date(period).toLocaleDateString(i18n.language === "ro" ? "ro-RO" : "en-US", { month: "long", year: "numeric" });
  const mismatchCount = rows.filter((r) => r.declarations.some((d) => d.mismatch)).length;
  const notEmailed = rows.filter((r) => r.declarations.length > 0 && !r.lastEmailAt).length;

  const selectable = rows.filter((r) => r.declarations.length > 0).map((r) => r.companyId);
  const allSelected = selectable.length > 0 && selectable.every((id) => selected.has(id));
  const toggle = (id: string) => setSelected((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const toggleAll = () => setSelected(allSelected ? new Set() : new Set(selectable));

  const refreshList = () => void qc.invalidateQueries({ queryKey: ["tax-list", period] });
  const openBulk = () => {
    const targets: PreviewTarget[] = rows.filter((r) => selected.has(r.companyId)).map((r) => ({
      companyId: r.companyId, companyName: r.companyName,
      declarationIds: [...new Set(r.declarations.map((d) => d.declarationId))],
    }));
    if (targets.length) setBulkTargets(targets);
  };

  return (
    <div style={{ display: "grid", gap: 16 }}>
      {/* Header */}
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end" }}>
        <div>
          <div style={{ color: "var(--text-secondary)", fontSize: 12.5 }}>{t("taxes.subtitle")} · {monthLabel}</div>
          <h2 style={{ margin: "2px 0 0", fontSize: 21, letterSpacing: "-0.01em" }}>{t("taxes.title")}</h2>
        </div>
        <div style={{ display: "flex", gap: 16, fontSize: 12, color: "var(--text-secondary)" }}>
          {mismatchCount > 0 && <span><Dot c="var(--dot-red)" /> {mismatchCount} {t("taxes.legendMismatch")}</span>}
          {notEmailed > 0 && <span><Dot c="var(--dot-orange)" /> {notEmailed} {t("taxes.legendNotEmailed")}</span>}
        </div>
      </div>

      {/* Bulk bar */}
      {selected.size > 0 && (
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", background: "var(--chrome-bg)", borderRadius: 10, padding: "9px 14px" }}>
          <span style={{ fontSize: 13.5, color: "var(--chrome-text)" }}><b style={{ color: "var(--primary)" }}>{selected.size}</b> {t("taxes.companiesSelected")}</span>
          <div style={{ display: "flex", gap: 8 }}>
            <button onClick={() => setSelected(new Set())} style={{ background: "var(--chrome-active)", color: "var(--chrome-text)", border: "1px solid #2a3a37" }}>{t("email.clear")}</button>
            <button className="primary" onClick={openBulk}><Icon name="mail" size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />{t("email.sendN", { n: selected.size })}</button>
          </div>
        </div>
      )}

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
                {DECLARATION_TYPES.map((ty) => (
                  <div key={ty} className="mono" style={{ textAlign: "right", fontSize: 12.5 }}>
                    <DeclStack cells={cellsFor(row, ty)} missingLabel={t("taxes.missing")} />
                  </div>
                ))}
                <div className="mono" style={{ textAlign: "right", fontSize: 12.5 }}>
                  <DeclStack cells={otherCells(row)} />
                </div>
                <div className="mono" style={{ textAlign: "right", fontWeight: 700, fontSize: 13 }}>
                  {total > 0
                    ? <InfoTip lines={breakdownLines(row.declarations, t("taxes.total"))}><span>{money(total)} RON</span></InfoTip>
                    : <span style={{ color: "var(--text-faint)", fontWeight: 400 }}>—</span>}
                </div>
                <div><LastEmailCell lastSentAt={row.lastEmailAt} count={row.emailCount} onOpen={() => setLogFor({ id: row.companyId, name: row.companyName })} /></div>
                <div><LastWhatsAppCell /></div>
                <div>
                  <RowActions>
                    <ActionBtn icon="upload" title={t("channel.upload")} onClick={() => setDeclFor({ id: row.companyId, name: row.companyName })} />
                    <ActionBtn icon="mail" title={t("channel.email")} onClick={() => setEmailFor({ id: row.companyId, name: row.companyName })} />
                    <WhatsAppAction />
                  </RowActions>
                </div>
              </div>
            );
          })}
          {data && rows.length === 0 && <div style={{ padding: 14, color: "var(--text-muted)" }}>{t("taxes.noCompanies")}</div>}
        </div>
      </div>

      {declFor && <DeclarationsModal companyId={declFor.id} companyName={declFor.name} period={period} onClose={() => { setDeclFor(null); refreshList(); }} />}
      {emailFor && <TaxPaymentModal companyId={emailFor.id} companyName={emailFor.name} period={period} onClose={() => { setEmailFor(null); refreshList(); }} />}
      {logFor && <NotificationLogModal companyId={logFor.id} companyName={logFor.name} period={period}
        onClose={() => { setLogFor(null); refreshList(); }}
        onCompose={() => setEmailFor({ id: logFor.id, name: logFor.name })} />}
      {bulkTargets && <EmailPreviewModal targets={bulkTargets} period={period}
        onClose={() => setBulkTargets(null)} onSent={() => { setSelected(new Set()); refreshList(); }} />}
    </div>
  );
}

function Dot({ c }: { c: string }) {
  return <span style={{ display: "inline-block", width: 8, height: 8, borderRadius: "50%", background: c, marginRight: 5 }} />;
}

const gridRow: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "30px minmax(200px,1.5fr) 96px 96px 96px 96px 100px 120px 110px 120px",
  alignItems: "center", gap: 10, padding: "10px 16px",
};
const thText: React.CSSProperties = { fontSize: 9.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "#8a9794" };
