import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { tenantsApi, TENANT_STATUSES, type Tenant, type TenantStatus } from "../api/tenants";
import { ApiError } from "../lib/apiClient";
import { Field } from "../components/Field";

const STATUS_STYLE: Record<TenantStatus, React.CSSProperties> = {
  ACTIVE: { background: "#ecfdf5", color: "#059669", border: "1px solid #a7f3d0" },
  SUSPENDED: { background: "#fffbeb", color: "#b45309", border: "1px solid #fde68a" },
  ARCHIVED: { background: "#f3f4f6", color: "#6b7280", border: "1px solid #e5e7eb" },
};

/** SUPER_ADMIN screen for managing the accounting-firm tenants (MOD-01). */
export function AdminTenants() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data: tenants = [], isLoading } = useQuery({ queryKey: ["admin-tenants"], queryFn: tenantsApi.list });
  const empty = { name: "", cui: "", plan: "" };
  const [form, setForm] = useState(empty);
  const [error, setError] = useState<string | null>(null);
  const invalidate = () => void qc.invalidateQueries({ queryKey: ["admin-tenants"] });

  const add = useMutation({
    mutationFn: () => tenantsApi.create({ name: form.name.trim(), cui: form.cui.trim() || null, plan: form.plan.trim() || null }),
    onSuccess: () => { invalidate(); setForm(empty); setError(null); },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Failed to add tenant"),
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!form.name.trim()) {
      setError(t("adminTenants.nameRequired"));
      return;
    }
    add.mutate();
  };

  const statusLabel = (s: TenantStatus) => t(`tenant.status.${s}`, { defaultValue: s });

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="card">
        <h1 style={{ marginTop: 0 }}>{t("adminTenants.title")}</h1>
        <p style={{ color: "var(--text-muted)", marginBottom: 0 }}>{t("adminTenants.intro")}</p>
      </div>

      <div className="card">
        {isLoading ? (
          <p>{t("common.loading")}</p>
        ) : (
          <table style={{ width: "100%", borderCollapse: "collapse", marginBottom: 16 }}>
            <thead>
              <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
                <th style={{ padding: 8 }}>{t("adminTenants.name")}</th>
                <th style={{ padding: 8 }}>{t("adminTenants.cui")}</th>
                <th style={{ padding: 8 }}>{t("adminTenants.plan")}</th>
                <th style={{ padding: 8 }}>{t("adminTenants.created")}</th>
                <th style={{ padding: 8 }}>{t("adminTenants.status")}</th>
              </tr>
            </thead>
            <tbody>
              {tenants.map((tenant) => (
                <TenantRow key={tenant.id} tenant={tenant} statusLabel={statusLabel} onChanged={invalidate} />
              ))}
              {tenants.length === 0 && (
                <tr>
                  <td colSpan={5} style={{ padding: 8, color: "var(--text-muted)" }}>{t("adminTenants.noTenants")}</td>
                </tr>
              )}
            </tbody>
          </table>
        )}

        {error && <p style={{ color: "#dc2626" }}>{error}</p>}
        <form onSubmit={submit} style={{ display: "flex", alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}>
          <Field label={t("adminTenants.name")}>
            <input required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="ContaZone SRL" style={{ minWidth: 200 }} />
          </Field>
          <Field label={t("adminTenants.cui")}>
            <input value={form.cui} onChange={(e) => setForm({ ...form, cui: e.target.value })}
              placeholder="RO12345678" style={{ maxWidth: 140 }} />
          </Field>
          <Field label={t("adminTenants.plan")}>
            <input value={form.plan} onChange={(e) => setForm({ ...form, plan: e.target.value })}
              placeholder="STANDARD" style={{ maxWidth: 140 }} />
          </Field>
          <button className="primary" type="submit" disabled={add.isPending} style={{ marginBottom: 10 }}>
            {add.isPending ? "…" : t("common.add")}
          </button>
        </form>
      </div>
    </div>
  );
}

function TenantRow({ tenant, statusLabel, onChanged }: {
  tenant: Tenant;
  statusLabel: (s: TenantStatus) => string;
  onChanged: () => void;
}) {
  const change = useMutation({
    mutationFn: (status: TenantStatus) => tenantsApi.changeStatus(tenant.id, status),
    onSuccess: onChanged,
  });

  return (
    <tr style={{ borderTop: "1px solid var(--border)" }}>
      <td style={{ padding: 8 }}><b>{tenant.name}</b></td>
      <td style={{ padding: 8 }}>{tenant.cui || "—"}</td>
      <td style={{ padding: 8 }}>{tenant.plan}</td>
      <td style={{ padding: 8, whiteSpace: "nowrap" }}>{tenant.createdAt.slice(0, 10)}</td>
      <td style={{ padding: 8 }}>
        <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
          <span style={{ fontSize: 11, padding: "2px 8px", borderRadius: 999, ...STATUS_STYLE[tenant.status] }}>
            {statusLabel(tenant.status)}
          </span>
          <select value={tenant.status} disabled={change.isPending}
            onChange={(e) => change.mutate(e.target.value as TenantStatus)}>
            {TENANT_STATUSES.map((s) => <option key={s} value={s}>{statusLabel(s)}</option>)}
          </select>
        </span>
      </td>
    </tr>
  );
}
