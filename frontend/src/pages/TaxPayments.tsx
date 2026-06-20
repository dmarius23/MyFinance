import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { taxPaymentsApi, DECLARATION_TYPES, type TaxPaymentRow } from "../api/taxes";
import { ApiError } from "../lib/apiClient";
import { usePeriod } from "../lib/period";
import { Icon } from "../components/Icon";
import { TaxPaymentModal } from "../components/TaxPaymentModal";
import { DeclarationsModal } from "../components/DeclarationsModal";

const money = (n: number) => n.toLocaleString("ro-RO", { minimumFractionDigits: 0 });
const dmy = (iso: string) => new Date(iso).toLocaleDateString("ro-RO", { day: "numeric", month: "short" });

function cellFor(row: TaxPaymentRow, type: string) {
  return row.declarations.find((d) => d.type === type);
}
function toPay(row: TaxPaymentRow) {
  return row.declarations.reduce((s, d) => s + d.amount, 0);
}

/** MOD-07 — Taxes & payments monthly list, Console (B) skin. */
export function TaxPayments() {
  const { t, i18n } = useTranslation();
  const qc = useQueryClient();
  const { period } = usePeriod();
  const [emailFor, setEmailFor] = useState<{ id: string; name: string } | null>(null);
  const [declFor, setDeclFor] = useState<{ id: string; name: string } | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ["tax-list", period],
    queryFn: () => taxPaymentsApi.list(period),
  });
  const rows = data ?? [];

  const monthLabel = new Date(period).toLocaleDateString(i18n.language === "ro" ? "ro-RO" : "en-US",
    { month: "long", year: "numeric" });
  const mismatchCount = rows.filter((r) => r.declarations.some((d) => d.mismatch)).length;
  const notEmailed = rows.filter((r) => r.declarations.length > 0 && !r.lastEmailAt).length;

  const refreshList = () => void qc.invalidateQueries({ queryKey: ["tax-list", period] });

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

      {/* List */}
      <div className="card" style={{ padding: 0, overflow: "hidden" }}>
        {isLoading && <p style={{ padding: 14 }}>{t("common.loading")}</p>}
        {error && <p style={{ padding: 14, color: "var(--danger-fg)" }}>{error instanceof ApiError ? error.message : "Failed to load"}</p>}

        <div style={{ minWidth: 920 }}>
          {/* header row */}
          <div style={{ ...gridRow, background: "var(--th-bg)", ...thText }}>
            <div>{t("documents.company")}</div>
            {DECLARATION_TYPES.map((ty) => <div key={ty} style={{ textAlign: "right" }}>{ty}</div>)}
            <div style={{ textAlign: "right" }}>{t("taxes.toPayCol")}</div>
            <div>{t("taxes.lastSent")}</div>
            <div style={{ textAlign: "right" }}>{t("statements.actions")}</div>
          </div>

          {rows.map((row) => {
            const total = toPay(row);
            return (
              <div key={row.companyId} style={{ ...gridRow, borderTop: "1px solid var(--hair)" }}>
                {/* company */}
                <div>
                  <div style={{ fontWeight: 600 }}>{row.companyName}</div>
                  <div className="mono" style={{ color: "var(--text-muted)", fontSize: 11 }}>
                    {row.cui}{row.residence ? ` · ${row.residence}` : ""}
                  </div>
                </div>
                {/* declaration amounts */}
                {DECLARATION_TYPES.map((ty) => {
                  const c = cellFor(row, ty);
                  return (
                    <div key={ty} className="mono" style={{ textAlign: "right", fontSize: 12.5 }}>
                      {c
                        ? <span>{money(c.amount)}{c.mismatch && <span title={t("taxes.mismatch")} style={{ color: "#b45309", marginLeft: 4 }}>⚠</span>}</span>
                        : <span style={{ color: "var(--text-faint)" }}>—</span>}
                    </div>
                  );
                })}
                {/* to pay */}
                <div className="mono" style={{ textAlign: "right", fontWeight: 700, fontSize: 13 }}>
                  {total > 0 ? money(total) : <span style={{ color: "var(--text-faint)", fontWeight: 400 }}>—</span>}
                </div>
                {/* last sent */}
                <div>
                  {row.lastEmailAt
                    ? <button className="pill teal round" style={pillBtn}
                        onClick={() => setEmailFor({ id: row.companyId, name: row.companyName })}>
                        <Icon name="mail" size={11} style={{ verticalAlign: "-1px", marginRight: 4 }} />
                        {dmy(row.lastEmailAt)}{row.emailCount > 1 ? ` · ${row.emailCount}` : ""}
                      </button>
                    : <button style={neverBtn} onClick={() => setEmailFor({ id: row.companyId, name: row.companyName })}>
                        {t("taxes.neverSent")} · <u>{t("taxes.sendShort")}</u>
                      </button>}
                </div>
                {/* actions */}
                <div style={{ display: "flex", gap: 6, justifyContent: "flex-end" }}>
                  <button style={iconBtn} title={t("taxes.manageDeclarations")}
                    onClick={() => setDeclFor({ id: row.companyId, name: row.companyName })}><Icon name="folder" size={14} /></button>
                  <button style={iconBtn} title={t("taxes.sendEmail")} disabled={row.declarations.length === 0}
                    onClick={() => setEmailFor({ id: row.companyId, name: row.companyName })}><Icon name="mail" size={14} /></button>
                </div>
              </div>
            );
          })}
          {data && rows.length === 0 && (
            <div style={{ padding: 14, color: "var(--text-muted)" }}>{t("taxes.noCompanies")}</div>
          )}
        </div>
      </div>

      {declFor && (
        <DeclarationsModal companyId={declFor.id} companyName={declFor.name} period={period}
          onClose={() => { setDeclFor(null); refreshList(); }} />
      )}
      {emailFor && (
        <TaxPaymentModal companyId={emailFor.id} companyName={emailFor.name} period={period}
          onClose={() => { setEmailFor(null); refreshList(); }} />
      )}
    </div>
  );
}

function Dot({ c }: { c: string }) {
  return <span style={{ display: "inline-block", width: 8, height: 8, borderRadius: "50%", background: c, marginRight: 5 }} />;
}

const gridRow: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "minmax(210px,1.5fr) 96px 96px 96px 110px 130px 96px",
  alignItems: "center", gap: 10, padding: "10px 16px",
};
const thText: React.CSSProperties = {
  fontSize: 9.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "#8a9794",
};
const iconBtn: React.CSSProperties = {
  width: 28, height: 28, display: "grid", placeItems: "center", padding: 0,
  border: "1px solid var(--border)", borderRadius: 8, background: "var(--surface)", color: "#52605d", cursor: "pointer",
};
const pillBtn: React.CSSProperties = { cursor: "pointer", border: "1px solid var(--teal-chip-bd)", font: "inherit" };
const neverBtn: React.CSSProperties = {
  background: "none", border: "1px dashed var(--border)", borderRadius: 999, padding: "1px 8px",
  fontSize: 11, color: "var(--primary-dark)", cursor: "pointer",
};
