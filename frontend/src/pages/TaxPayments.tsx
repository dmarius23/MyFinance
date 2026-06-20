import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { ApiError } from "../lib/apiClient";
import { MonthBar } from "../components/MonthBar";
import { TaxPaymentModal } from "../components/TaxPaymentModal";

/** MOD-07 — Taxes & payments: per-company computed state-payment amounts from ANAF declarations. */
export function TaxPayments() {
  const { t } = useTranslation();
  const [period, setPeriod] = useState(() => new Date().toISOString().slice(0, 7) + "-01");
  const [openFor, setOpenFor] = useState<{ id: string; name: string } | null>(null);

  const companies = useQuery({ queryKey: ["companies"], queryFn: companiesApi.list });
  const rows = companies.data ?? [];

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="card">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h1 style={{ marginTop: 0 }}>{t("taxes.title")}</h1>
          <MonthBar value={period} onChange={setPeriod} />
        </div>
      </div>

      <div className="card">
        {companies.isLoading && <p>{t("common.loading")}</p>}
        {companies.error && (
          <p style={{ color: "#dc2626" }}>
            {companies.error instanceof ApiError ? companies.error.message : "Failed to load companies"}
          </p>
        )}
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8 }}>{t("documents.company")}</th>
              <th style={{ padding: 8 }}>{t("company.fiscalResidence")}</th>
              <th style={{ padding: 8, textAlign: "right" }} />
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => (
              <tr key={c.id} style={{ borderTop: "1px solid var(--border)" }}>
                <td style={{ padding: 8 }}>
                  <b>{c.legalName}</b>
                  <div style={{ color: "var(--text-muted)", fontSize: 12 }}>{c.cui}</div>
                </td>
                <td style={{ padding: 8 }}>{c.locality ?? "—"}</td>
                <td style={{ padding: 8, textAlign: "right" }}>
                  <button onClick={() => setOpenFor({ id: c.id, name: c.legalName })}>
                    {t("taxes.viewPayments")}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {openFor && (
        <TaxPaymentModal companyId={openFor.id} companyName={openFor.name} period={period}
          onClose={() => setOpenFor(null)} />
      )}
    </div>
  );
}
