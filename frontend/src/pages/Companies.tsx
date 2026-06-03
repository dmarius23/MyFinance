import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { ApiError } from "../lib/apiClient";
import { AddCompanyModal } from "../components/AddCompanyModal";

/** MOD-03 — manage companies: list + add; rows link to detail. */
export function Companies() {
  const [showAdd, setShowAdd] = useState(false);
  const { data, isLoading, error } = useQuery({
    queryKey: ["companies"],
    queryFn: companiesApi.list,
  });

  return (
    <div className="card">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h1 style={{ marginTop: 0 }}>Companies</h1>
        <button className="primary" onClick={() => setShowAdd(true)}>Add company</button>
      </div>

      {isLoading && <p>Loading…</p>}
      {error && (
        <p style={{ color: "#dc2626" }}>
          {error instanceof ApiError ? error.message : "Failed to load companies"}
        </p>
      )}

      {data && (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8 }}>Legal name</th>
              <th style={{ padding: 8 }}>CUI</th>
              <th style={{ padding: 8 }}>Type</th>
              <th style={{ padding: 8 }}>Locality</th>
              <th style={{ padding: 8 }}>VAT</th>
              <th style={{ padding: 8 }}>Status</th>
            </tr>
          </thead>
          <tbody>
            {data.map((c) => (
              <tr key={c.id} style={{ borderTop: "1px solid var(--border)" }}>
                <td style={{ padding: 8 }}><Link to={`/companies/${c.id}`}>{c.legalName}</Link></td>
                <td style={{ padding: 8 }}>{c.cui}</td>
                <td style={{ padding: 8 }}>{c.entityType ?? "—"}</td>
                <td style={{ padding: 8 }}>{c.locality ?? "—"}</td>
                <td style={{ padding: 8 }}>{c.vatStatus ?? "—"}</td>
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
