import { useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { companiesApi, taxRegimeKey } from "../api/companies";
import { ApiError } from "../lib/apiClient";
import { AddCompanyModal } from "../components/AddCompanyModal";
import { vatStatusKey } from "../domain/vat";

/** MOD-03 — manage companies: list + add; rows link to detail. */
export function Companies() {
  const { t } = useTranslation();
  const [showAdd, setShowAdd] = useState(false);
  const { data, isLoading, error } = useQuery({
    queryKey: ["companies"],
    queryFn: companiesApi.list,
  });

  return (
    <div className="card">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h1 style={{ marginTop: 0 }}>{t("nav.companies")}</h1>
        <button className="primary" onClick={() => setShowAdd(true)}>{t("companies.add")}</button>
      </div>

      {isLoading && <p>{t("common.loading")}</p>}
      {error && (
        <p style={{ color: "#dc2626" }}>
          {error instanceof ApiError ? error.message : t("companies.loadError")}
        </p>
      )}

      {data && (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8 }}>{t("company.legalName")}</th>
              <th style={{ padding: 8 }}>{t("company.cui")}</th>
              <th style={{ padding: 8 }}>{t("company.entityType")}</th>
              <th style={{ padding: 8 }}>{t("company.locality")}</th>
              <th style={{ padding: 8 }}>{t("company.vat")}</th>
              <th style={{ padding: 8 }}>{t("company.taxRegime")}</th>
              <th style={{ padding: 8 }}>{t("company.hasEmployees")}</th>
              <th style={{ padding: 8 }}>{t("company.status")}</th>
            </tr>
          </thead>
          <tbody>
            {data.map((c) => (
              <tr key={c.id} style={{ borderTop: "1px solid var(--border)" }}>
                <td style={{ padding: 8 }}><Link to={`/companies/${c.id}`}>{c.legalName}</Link></td>
                <td style={{ padding: 8 }}>{c.cui}</td>
                <td style={{ padding: 8 }}>{c.entityType ?? "—"}</td>
                <td style={{ padding: 8 }}>{c.locality ?? "—"}</td>
                <td style={{ padding: 8 }}>{c.vatStatus ? t(vatStatusKey(c.vatStatus), { defaultValue: c.vatStatus }) : "—"}</td>
                <td style={{ padding: 8 }}>{c.taxRegime ? t(taxRegimeKey(c.taxRegime), { defaultValue: c.taxRegime }) : "—"}</td>
                <td style={{ padding: 8 }}>{c.hasEmployees == null ? "—" : t(c.hasEmployees ? "common.yes" : "common.no")}</td>
                <td style={{ padding: 8 }}>{c.status}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {showAdd && <AddCompanyModal onClose={() => setShowAdd(false)} />}
    </div>
  );
}
