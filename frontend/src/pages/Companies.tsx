import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { companiesApi, representativesApi, taxRegimeKey, type Company, type CompanyRepEntry } from "../api/companies";
import { ApiError } from "../lib/apiClient";
import { AddCompanyModal } from "../components/AddCompanyModal";
import { ImportCompaniesModal } from "../components/ImportCompaniesModal";
import { CompanySearch } from "../components/CompanySearch";
import { vatStatusKey } from "../domain/vat";
import { ENTITY_TYPES } from "../domain/company";

const TAX_REGIMES = ["MICRO", "PROFIT"] as const;
const norm = (s: string) => s.toLowerCase().normalize("NFD").replace(/\p{Diacritic}/gu, "");
type SortKey = "name" | "residence" | "created";

/** A company is "complete" when it has a representative AND the mandatory fiscal profile (residence, VAT,
 *  tax regime). Anything missing shows the "incomplet" chip and fails the completeness filter. */
function isComplete(c: Company, hasRep: boolean): boolean {
  return hasRep && !!c.locality && !!c.vatStatus && !!c.taxRegime;
}

/** MOD-03 — manage companies: filterable/sortable list; whole row opens detail. */
export function Companies() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const [showAdd, setShowAdd] = useState(false);
  const [showImport, setShowImport] = useState(false);
  const [dq, setDq] = useState("");
  const [activeOnly, setActiveOnly] = useState(true);
  const [fVat, setFVat] = useState("");        // "" | VAT_PAYER | NON_VAT_PAYER
  const [fType, setFType] = useState("");       // "" | SRL | SA | PFA | ONG
  const [fRegime, setFRegime] = useState("");   // "" | MICRO | PROFIT
  const [fEmp, setFEmp] = useState("");         // "" | yes | no
  const [fComplete, setFComplete] = useState(""); // "" | complete | incomplete
  const [sort, setSort] = useState<SortKey>("name");

  const companiesQ = useQuery({ queryKey: ["companies-all"], queryFn: companiesApi.list });
  const repsQ = useQuery({ queryKey: ["representatives-all"], queryFn: representativesApi.listAll });

  const repsByCompany = useMemo(() => {
    const m = new Map<string, CompanyRepEntry[]>();
    for (const r of repsQ.data ?? []) {
      const list = m.get(r.companyId) ?? [];
      list.push(r);
      m.set(r.companyId, list);
    }
    return m;
  }, [repsQ.data]);

  const rows = useMemo(() => {
    const all = companiesQ.data ?? [];
    const q = norm(dq.trim());
    const filtered = all.filter((c) => {
      if (activeOnly && c.status !== "ACTIVE") return false;
      if (q && !(norm(c.legalName).includes(q) || c.cui.toLowerCase().includes(dq.trim().toLowerCase()))) return false;
      if (fVat && c.vatStatus !== fVat) return false;
      if (fType && c.entityType !== fType) return false;
      if (fRegime && c.taxRegime !== fRegime) return false;
      if (fEmp && (fEmp === "yes") !== (c.hasEmployees === true)) return false;
      if (fComplete) {
        const complete = isComplete(c, (repsByCompany.get(c.id) ?? []).length > 0);
        if (fComplete === "complete" && !complete) return false;
        if (fComplete === "incomplete" && complete) return false;
      }
      return true;
    });
    if (sort === "created") {
      // Newest first (ISO timestamps sort lexicographically); ties fall back to name.
      return filtered.sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? "") || norm(a.legalName).localeCompare(norm(b.legalName)));
    }
    const key = sort === "residence"
      ? (c: Company) => `${norm(c.locality ?? "￿")}|${norm(c.legalName)}`
      : (c: Company) => norm(c.legalName);
    return filtered.sort((a, b) => key(a).localeCompare(key(b)));
  }, [companiesQ.data, dq, activeOnly, fVat, fType, fRegime, fEmp, fComplete, sort, repsByCompany]);

  const fmtDate = (iso: string) => new Date(iso).toLocaleDateString(i18n.language === "ro" ? "ro-RO" : "en-US",
    { day: "2-digit", month: "short", year: "numeric" });

  const selectStyle: React.CSSProperties = { fontSize: 12, padding: "5px 8px", borderRadius: 8,
    border: "1px solid var(--border)", background: "var(--surface)", color: "var(--text-secondary)" };

  return (
    <div className="card">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
        <h1 style={{ marginTop: 0 }}>{t("nav.companies")}</h1>
        <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
          <CompanySearch onSearch={setDq} />
          <button onClick={() => setShowImport(true)}>{t("companies.import")}</button>
          <button className="primary" onClick={() => setShowAdd(true)}>{t("companies.add")}</button>
        </div>
      </div>

      {/* Filter bar */}
      <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap", margin: "10px 0 4px" }}>
        <select value={fComplete} onChange={(e) => setFComplete(e.target.value)} style={selectStyle} title={t("companies.filter.completeness")}>
          <option value="">{t("companies.filter.completeness")}: {t("filter.all")}</option>
          <option value="complete">{t("companies.filter.complete")}</option>
          <option value="incomplete">{t("company.incomplete")}</option>
        </select>
        <select value={fVat} onChange={(e) => setFVat(e.target.value)} style={selectStyle}>
          <option value="">{t("company.vat")}: {t("filter.all")}</option>
          <option value="VAT_PAYER">{t("vatStatus.VAT_PAYER")}</option>
          <option value="NON_VAT_PAYER">{t("vatStatus.NON_VAT_PAYER")}</option>
        </select>
        <select value={fType} onChange={(e) => setFType(e.target.value)} style={selectStyle}>
          <option value="">{t("company.entityType")}: {t("filter.all")}</option>
          {ENTITY_TYPES.map((v) => <option key={v} value={v}>{v}</option>)}
        </select>
        <select value={fRegime} onChange={(e) => setFRegime(e.target.value)} style={selectStyle}>
          <option value="">{t("company.taxRegime")}: {t("filter.all")}</option>
          {TAX_REGIMES.map((v) => <option key={v} value={v}>{t(taxRegimeKey(v))}</option>)}
        </select>
        <select value={fEmp} onChange={(e) => setFEmp(e.target.value)} style={selectStyle}>
          <option value="">{t("company.hasEmployees")}: {t("filter.all")}</option>
          <option value="yes">{t("common.yes")}</option>
          <option value="no">{t("common.no")}</option>
        </select>
        <label style={{ display: "flex", alignItems: "center", gap: 5, fontSize: 12, color: "var(--text-secondary)" }}>
          <input type="checkbox" checked={activeOnly} onChange={(e) => setActiveOnly(e.target.checked)} />
          {t("companies.filter.activeOnly")}
        </label>
        <span style={{ flex: 1 }} />
        <select value={sort} onChange={(e) => setSort(e.target.value as SortKey)} style={selectStyle} title={t("companies.sort")}>
          <option value="name">{t("companies.sort")}: {t("company.legalName")}</option>
          <option value="residence">{t("companies.sort")}: {t("company.locality")}</option>
          <option value="created">{t("companies.sort")}: {t("company.created")}</option>
        </select>
      </div>

      {(companiesQ.isLoading || repsQ.isLoading) && <p>{t("common.loading")}</p>}
      {companiesQ.error && (
        <p style={{ color: "#dc2626" }}>
          {companiesQ.error instanceof ApiError ? companiesQ.error.message : t("companies.loadError")}
        </p>
      )}

      {companiesQ.data && (
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
              <th style={{ padding: 8 }}>{t("company.created")}</th>
              <th style={{ padding: 8 }}>{t("company.status")}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => {
              const reps = repsByCompany.get(c.id) ?? [];
              const complete = isComplete(c, reps.length > 0);
              return (
                <tr key={c.id} onClick={() => navigate(`/companies/${c.id}`)}
                  style={{ borderTop: "1px solid var(--border)", cursor: "pointer" }}
                  onMouseEnter={(e) => (e.currentTarget.style.background = "var(--row-active)")}
                  onMouseLeave={(e) => (e.currentTarget.style.background = "")}>
                  <td style={{ padding: 8 }}>
                    <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
                      <span style={{ fontWeight: 600 }}>{c.legalName}</span>
                      {!complete && (
                        <span className="pill round warn" title={t("company.incompleteHint")}>⚠ {t("company.incomplete")}</span>
                      )}
                    </span>
                  </td>
                  <td style={{ padding: 8 }} className="mono">{c.cui}</td>
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
                            {r.status === "INACTIVE" && <span className="pill round muted" style={{ fontSize: 10 }}>{t("team.st.INACTIVE")}</span>}
                            {r.status === "INVITED" && <span className="pill round" style={{ fontSize: 10 }}>{t("team.st.INVITED")}</span>}
                          </span>
                        ))}
                      </div>
                    )}
                  </td>
                  <td style={{ padding: 8, color: "var(--text-muted)", whiteSpace: "nowrap" }}>{fmtDate(c.createdAt)}</td>
                  <td style={{ padding: 8 }}>{t(`companyStatus.${c.status}`, { defaultValue: c.status })}</td>
                </tr>
              );
            })}
            {rows.length === 0 && !companiesQ.isLoading && (
              <tr><td colSpan={10} style={{ padding: 14, textAlign: "center", color: "var(--text-muted)" }}>{t("taxes.noCompanies")}</td></tr>
            )}
          </tbody>
        </table>
      )}

      {showAdd && <AddCompanyModal onClose={() => setShowAdd(false)} />}
      {showImport && <ImportCompaniesModal onClose={() => setShowImport(false)} />}
    </div>
  );
}
