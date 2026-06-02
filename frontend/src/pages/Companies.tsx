import { useQuery } from "@tanstack/react-query";
import { api, ApiError } from "../lib/apiClient";

interface CompanyResponse {
  id: string;
  legalName: string;
  cui: string;
  entityType: string | null;
  locality: string | null;
  vatStatus: string | null;
  status: string;
}

/** MOD-03 — manage companies. Demonstrates the typed API + TanStack Query wiring end-to-end. */
export function Companies() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["companies"],
    queryFn: () => api<CompanyResponse[]>("/api/v1/companies"),
  });

  if (isLoading) {
    return <div className="card">Loading…</div>;
  }
  if (error) {
    const message = error instanceof ApiError ? error.message : "Failed to load companies";
    return (
      <div className="card">
        <h1 style={{ marginTop: 0 }}>Companies</h1>
        <p style={{ color: "#dc2626" }}>{message}</p>
        <p style={{ color: "var(--text-muted)" }}>
          Expected until Supabase auth + a tenant JWT are configured. Sign in first.
        </p>
      </div>
    );
  }

  return (
    <div className="card">
      <h1 style={{ marginTop: 0 }}>Companies</h1>
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
          {(data ?? []).map((c) => (
            <tr key={c.id} style={{ borderTop: "1px solid var(--border)" }}>
              <td style={{ padding: 8 }}>{c.legalName}</td>
              <td style={{ padding: 8 }}>{c.cui}</td>
              <td style={{ padding: 8 }}>{c.entityType ?? "—"}</td>
              <td style={{ padding: 8 }}>{c.locality ?? "—"}</td>
              <td style={{ padding: 8 }}>{c.vatStatus ?? "—"}</td>
              <td style={{ padding: 8 }}>{c.status}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
