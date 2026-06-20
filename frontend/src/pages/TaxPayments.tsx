import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { taxPaymentsApi, DECLARATION_TYPES, type TaxPaymentRow } from "../api/taxes";
import { ApiError } from "../lib/apiClient";
import { MonthBar } from "../components/MonthBar";
import { TaxPaymentModal } from "../components/TaxPaymentModal";
import { DeclarationsModal } from "../components/DeclarationsModal";

const money = (n: number) => n.toLocaleString("ro-RO");
const dmy = (iso: string) => new Date(iso).toLocaleDateString("ro-RO");

/** MOD-07 — monthly Tax & payments list: a row per company, a column per declaration, email status. */
export function TaxPayments() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [period, setPeriod] = useState(() => new Date().toISOString().slice(0, 7) + "-01");
  const [emailFor, setEmailFor] = useState<{ id: string; name: string } | null>(null);
  const [declFor, setDeclFor] = useState<{ id: string; name: string } | null>(null);

  const { data, isLoading, error: loadErr } = useQuery({
    queryKey: ["tax-list", period],
    queryFn: () => taxPaymentsApi.list(period),
  });

  const cellFor = (row: TaxPaymentRow, type: string) => row.declarations.find((d) => d.type === type);

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="card">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h1 style={{ marginTop: 0 }}>{t("taxes.title")}</h1>
          <MonthBar value={period} onChange={setPeriod} />
        </div>
      </div>

      <div className="card">
        {isLoading && <p>{t("common.loading")}</p>}
        {loadErr && <p style={{ color: "#dc2626" }}>{loadErr instanceof ApiError ? loadErr.message : "Failed to load"}</p>}

        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8 }}>{t("documents.company")}</th>
              {DECLARATION_TYPES.map((type) => <th key={type} style={{ padding: 8, textAlign: "center" }}>{type}</th>)}
              <th style={{ padding: 8 }}>{t("taxes.emailStatus")}</th>
              <th style={{ padding: 8, textAlign: "right" }}>{t("statements.actions")}</th>
            </tr>
          </thead>
          <tbody>
            {(data ?? []).map((row) => (
              <tr key={row.companyId} style={{ borderTop: "1px solid var(--border)" }}>
                <td style={{ padding: 8 }}>
                  <b>{row.companyName}</b>
                  <div style={{ color: "var(--text-muted)", fontSize: 12 }}>
                    {row.cui}{row.residence ? ` · ${row.residence}` : ""}
                  </div>
                </td>
                {DECLARATION_TYPES.map((type) => {
                  const c = cellFor(row, type);
                  return (
                    <td key={type} style={{ padding: 8, textAlign: "center" }}>
                      {c
                        ? <span title={c.mismatch ? t("taxes.mismatch") : ""}>
                            {money(c.amount)}{c.mismatch ? " ⚠" : ""}
                          </span>
                        : <span style={{ color: "var(--text-muted)" }}>—</span>}
                    </td>
                  );
                })}
                <td style={{ padding: 8 }}>
                  {row.lastEmailAt
                    ? <span style={{ color: "#166534", fontSize: 13 }}>
                        ✓ {dmy(row.lastEmailAt)}{row.emailCount > 1 ? ` (${row.emailCount})` : ""}
                      </span>
                    : <span style={{ color: "var(--text-muted)", fontSize: 13 }}>{t("taxes.notSent")}</span>}
                </td>
                <td style={{ padding: 8, textAlign: "right", whiteSpace: "nowrap" }}>
                  <button style={iconBtn} title={t("taxes.manageDeclarations")}
                    onClick={() => setDeclFor({ id: row.companyId, name: row.companyName })}>📤</button>
                  <button style={iconBtn} title={t("taxes.sendEmail")}
                    disabled={row.declarations.length === 0}
                    onClick={() => setEmailFor({ id: row.companyId, name: row.companyName })}>✉</button>
                </td>
              </tr>
            ))}
            {data && data.length === 0 && (
              <tr><td colSpan={DECLARATION_TYPES.length + 3} style={{ padding: 8, color: "var(--text-muted)" }}>
                {t("taxes.noCompanies")}
              </td></tr>
            )}
          </tbody>
        </table>
      </div>

      {declFor && (
        <DeclarationsModal companyId={declFor.id} companyName={declFor.name} period={period}
          onClose={() => { setDeclFor(null); void qc.invalidateQueries({ queryKey: ["tax-list", period] }); }} />
      )}
      {emailFor && (
        <TaxPaymentModal companyId={emailFor.id} companyName={emailFor.name} period={period}
          onClose={() => { setEmailFor(null); void qc.invalidateQueries({ queryKey: ["tax-list", period] }); }} />
      )}
    </div>
  );
}

const iconBtn: React.CSSProperties = {
  background: "none", border: "1px solid var(--border)", borderRadius: 8,
  cursor: "pointer", fontSize: 15, lineHeight: 1, padding: "5px 8px", marginLeft: 6,
};
