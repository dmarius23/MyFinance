import { useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { companiesApi, representativesApi, taxRegimeKey } from "../api/companies";
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

  const { data: repsData } = useQuery({
    queryKey: ["representatives-all"],
    queryFn: representativesApi.listAll,
  });

  // Group reps by companyId for O(1) lookup in the table rows.
  const repsByCompany = new Map<string, typeof repsData>([]);
  for (const r of repsData ?? []) {
    const list = repsByCompany.get(r.companyId) ?? [];
    list.push(r);
    repsByCompany.set(r.companyId, list);
  }

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
              <th style={{ padding: 8 }}>{t("company.representatives")}</th>
              <th style={{ padding: 8 }}>{t("company.status")}</th>
            </tr>
          </thead>
          <tbody>
            {data.map((c) => {
              const reps = repsByCompany.get(c.id) ?? [];
              return (
              <tr key={c.id} style={{ borderTop: "1px solid var(--border)" }}>
                <td style={{ padding: 8 }}><Link to={`/companies/${c.id}`}>{c.legalName}</Link></td>
                <td style={{ padding: 8 }}>{c.cui}</td>
                <td style={{ padding: 8 }}>{c.entityType ?? "—"}</td>
                <td style={{ padding: 8 }}>{c.locality ?? "—"}</td>
                <td style={{ padding: 8 }}>{c.vatStatus ? t(vatStatusKey(c.vatStatus), { defaultValue: c.vatStatus }) : "—"}</td>
                <td style={{ padding: 8 }}>{c.taxRegime ? t(taxRegimeKey(c.taxRegime), { defaultValue: c.taxRegime }) : "—"}</td>
                <td style={{ padding: 8 }}>{c.hasEmployees == null ? "—" : t(c.hasEmployees ? "common.yes" : "common.no")}</td>
                <td style={{ padding: 8 }}>
                  {reps.length === 0 ? (
                    <span style={{ color: "var(--text-muted)" }}>—</span>
                  ) : (
                    <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
                      {reps.map((r) => (
                        <span key={r.id} style={{ display: "flex", alignItems: "center", gap: 5 }}>
                          <span style={{ fontSize: 13 }}>{r.name}</span>
                          {r.status === "INACTIVE" && (
                            <span style={{ fontSize: 10, padding: "1px 5px", borderRadius: 999,
                              background: "#f3f4f6", color: "#6b7280", border: "1px solid #e5e7eb" }}>
                              {t("team.st.INACTIVE")}
                            </span>
                          )}
                          {r.status === "INVITED" && (
                            <span style={{ fontSize: 10, padding: "1px 5px", borderRadius: 999,
                              background: "#eff6ff", color: "#3b82f6", border: "1px solid #bfdbfe" }}>
                              {t("team.st.INVITED")}
                            </span>
                          )}
                        </span>
                      ))}
                    </div>
                  )}
                </td>
                <td style={{ padding: 8 }}>{c.status}</td>
              </tr>
              );
            })}
          </tbody>
        </table>
      )}

      {showAdd && <AddCompanyModal onClose={() => setShowAdd(false)} />}
    </div>
  );
}
