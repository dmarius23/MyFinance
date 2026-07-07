import { useState } from "react";
import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { companiesApi, TAX_REGIMES, taxRegimeKey, type Company } from "../api/companies";
import { representativesApi } from "../api/representatives";
import { ApiError } from "../lib/apiClient";
import { VAT_STATUSES, vatStatusKey } from "../domain/vat";
import { ENTITY_TYPES, VAT_PERIODS, vatPeriodKey } from "../domain/company";
import { ROMANIAN_LOCALITIES } from "../domain/localities";
import { Field } from "../components/Field";

/** Client company detail: general info (view/edit), representatives. */
export function CompanyDetail() {
  const { id = "" } = useParams();
  const company = useQuery({ queryKey: ["company", id], queryFn: () => companiesApi.get(id) });

  if (company.isLoading) return <div className="card">Loading…</div>;
  if (company.error)
    return (
      <div className="card">
        <p style={{ color: "#dc2626" }}>
          {company.error instanceof ApiError ? company.error.message : "Failed to load company"}
        </p>
      </div>
    );

  const c = company.data!;
  return (
    <div style={{ display: "grid", gap: 16 }}>
      <GeneralInfoSection company={c} />
      <RepresentativesSection companyId={id} />
    </div>
  );
}

function GeneralInfoSection({ company }: { company: Company }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState(() => toForm(company));

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ["company", company.id] });
    void qc.invalidateQueries({ queryKey: ["companies"] });
  };

  const save = useMutation({
    mutationFn: () => companiesApi.update(company.id, form),
    onSuccess: () => { invalidate(); setEditing(false); },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Failed to save"),
  });

  const toggleStatus = useMutation({
    mutationFn: () =>
      companiesApi.setStatus(company.id, company.status === "ACTIVE" ? "INACTIVE" : "ACTIVE"),
    onSuccess: invalidate,
  });

  if (!editing) {
    return (
      <div className="card">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h1 style={{ marginTop: 0 }}>{company.legalName}</h1>
          <div style={{ display: "flex", gap: 8 }}>
            <button onClick={() => { setForm(toForm(company)); setError(null); setEditing(true); }}>
              Edit
            </button>
            <button onClick={() => toggleStatus.mutate()} disabled={toggleStatus.isPending}>
              {company.status === "ACTIVE" ? "Deactivate" : "Activate"}
            </button>
          </div>
        </div>
        <dl style={grid}>
          <Row k="CUI" v={company.cui} />
          <Row k={t("company.entityType")} v={company.entityType ?? "—"} />
          <Row k={t("company.fiscalResidence")} v={company.locality ?? "—"} />
          <Row k={t("company.vatStatus")} v={company.vatStatus ? t(vatStatusKey(company.vatStatus), { defaultValue: company.vatStatus }) : "—"} />
          <Row k={t("company.vatPeriod")} v={company.vatPeriod ? t(vatPeriodKey(company.vatPeriod), { defaultValue: company.vatPeriod }) : "—"} />
          <Row k={t("company.taxRegime")} v={company.taxRegime ? t(taxRegimeKey(company.taxRegime), { defaultValue: company.taxRegime }) : "—"} />
          <Row k={t("company.hasEmployees")} v={company.hasEmployees == null ? "—" : t(company.hasEmployees ? "common.yes" : "common.no")} />
          <Row k="Status" v={company.status} />
        </dl>
      </div>
    );
  }

  return (
    <div className="card">
      <h1 style={{ marginTop: 0 }}>Edit company</h1>
      <form onSubmit={(e) => { e.preventDefault(); setError(null); save.mutate(); }}>
        <Field label="Legal name *">
          <input required value={form.legalName} onChange={(e) => setForm({ ...form, legalName: e.target.value })} />
        </Field>
        <Field label="CUI *">
          <input required value={form.cui} onChange={(e) => setForm({ ...form, cui: e.target.value })} />
          <span style={{ display: "block", color: "var(--text-muted)", fontSize: 12, marginTop: 2 }}>
            {t("company.cuiEditHint")}
          </span>
        </Field>
        <Field label={t("company.entityType")}>
          <select value={form.entityType} onChange={(e) => setForm({ ...form, entityType: e.target.value })}>
            <option value="">—</option>
            {ENTITY_TYPES.map((v) => <option key={v} value={v}>{v}</option>)}
          </select>
        </Field>
        <Field label={t("company.fiscalResidence")}>
          <input list="ro-localities" value={form.locality} placeholder={t("company.fiscalResidencePlaceholder")}
            onChange={(e) => setForm({ ...form, locality: e.target.value })} />
          <datalist id="ro-localities">
            {ROMANIAN_LOCALITIES.map((l) => <option key={l} value={l} />)}
          </datalist>
        </Field>
        <Field label={t("company.vatStatus")}>
          <select value={form.vatStatus} onChange={(e) => setForm({ ...form, vatStatus: e.target.value })}>
            <option value="">—</option>
            {VAT_STATUSES.map((v) => (
              <option key={v} value={v}>{t(vatStatusKey(v))}</option>
            ))}
          </select>
        </Field>
        <Field label={t("company.vatPeriod")}>
          <select value={form.vatPeriod} onChange={(e) => setForm({ ...form, vatPeriod: e.target.value })}>
            <option value="">—</option>
            {VAT_PERIODS.map((v) => (
              <option key={v} value={v}>{t(vatPeriodKey(v))}</option>
            ))}
          </select>
        </Field>
        <Field label={t("company.taxRegime")}>
          <select value={form.taxRegime} onChange={(e) => setForm({ ...form, taxRegime: e.target.value })}>
            <option value="">—</option>
            {TAX_REGIMES.map((v) => <option key={v} value={v}>{t(taxRegimeKey(v))}</option>)}
          </select>
        </Field>
        <label style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 10, fontSize: 13 }}>
          <input type="checkbox" style={{ width: "auto" }} checked={form.hasEmployees}
            onChange={(e) => setForm({ ...form, hasEmployees: e.target.checked })} />
          {t("company.hasEmployees")}
        </label>
        {error && <p style={{ color: "#dc2626" }}>{error}</p>}
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
          <button type="button" onClick={() => setEditing(false)}>Cancel</button>
          <button className="primary" type="submit" disabled={save.isPending}>
            {save.isPending ? "Saving…" : t("common.save")}
          </button>
        </div>
      </form>
    </div>
  );
}

function RepresentativesSection({ companyId }: { companyId: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const reps = useQuery({ queryKey: ["reps", companyId], queryFn: () => representativesApi.list(companyId) });
  const [form, setForm] = useState({ name: "", email: "", phone: "" });
  const [error, setError] = useState<string | null>(null);
  const onErr = (e: unknown) => setError(e instanceof ApiError ? e.message : "Action failed");
  const refresh = () => { void qc.invalidateQueries({ queryKey: ["reps", companyId] }); setError(null); };
  const invite = useMutation({
    mutationFn: () => representativesApi.invite(companyId, { name: form.name, email: form.email, phone: form.phone || undefined }),
    onSuccess: () => { refresh(); setForm({ name: "", email: "", phone: "" }); },
    onError: onErr,
  });
  const setActive = useMutation({
    mutationFn: ({ userId, active }: { userId: string; active: boolean }) => representativesApi.setActive(companyId, userId, active),
    onSuccess: refresh, onError: onErr,
  });
  const remove = useMutation({
    mutationFn: (userId: string) => representativesApi.remove(companyId, userId),
    onSuccess: refresh, onError: onErr,
  });
  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>Representatives</h2>
      {reps.isLoading && <p>Loading…</p>}
      <ul style={{ listStyle: "none", padding: 0, margin: 0, display: "grid", gap: 6 }}>
        {(reps.data ?? []).map((r) => (
          <li key={r.id} style={{ display: "flex", alignItems: "center", gap: 8, opacity: r.status === "INACTIVE" ? 0.55 : 1 }}>
            <span style={{ flex: 1, minWidth: 0 }}>
              {r.name ?? r.email} — {r.email}{r.phone ? ` — ${r.phone}` : ""}{" "}
              <span style={pill}>{r.status}</span>
            </span>
            <button type="button" disabled={setActive.isPending}
              onClick={() => setActive.mutate({ userId: r.id, active: r.status === "INACTIVE" })}>
              {r.status === "INACTIVE" ? "Activate" : "Deactivate"}
            </button>
            <button type="button" style={{ color: "#dc2626" }} disabled={remove.isPending}
              onClick={() => { if (window.confirm(`Remove ${r.name ?? r.email} from this company?`)) remove.mutate(r.id); }}>
              Remove
            </button>
          </li>
        ))}
        {reps.data?.length === 0 && (
          <li style={{ color: "var(--text-muted)" }}>No representatives yet</li>
        )}
      </ul>
      {error && <p style={{ color: "#dc2626" }}>{error}</p>}
      <form
        style={{ display: "flex", gap: 8, marginTop: 8 }}
        onSubmit={(e) => { e.preventDefault(); invite.mutate(); }}
      >
        <input placeholder={t("company.namePlaceholder")} required value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })} />
        <input type="email" placeholder={t("company.emailPlaceholder")} required value={form.email}
          onChange={(e) => setForm({ ...form, email: e.target.value })} />
        <input type="tel" placeholder={t("company.phonePlaceholder")} value={form.phone}
          onChange={(e) => setForm({ ...form, phone: e.target.value })} />
        <button className="primary" type="submit" disabled={invite.isPending}>{t("company.invite")}</button>
      </form>
    </div>
  );
}

function toForm(c: Company) {
  return {
    cui: c.cui,
    legalName: c.legalName,
    entityType: c.entityType ?? "",
    locality: c.locality ?? "",
    vatStatus: c.vatStatus ?? "",
    vatPeriod: c.vatPeriod ?? "",
    taxRegime: c.taxRegime ?? "",
    hasEmployees: c.hasEmployees ?? false,
    // Round-trip so saving the edit form doesn't unassign the responsible accountant
    // (the backend overwrites responsibleUserId on every update).
    responsibleUserId: c.responsibleUserId ?? undefined,
  };
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <>
      <dt style={{ color: "var(--text-muted)" }}>{k}</dt>
      <dd style={{ margin: 0 }}>{v}</dd>
    </>
  );
}

const grid: React.CSSProperties = { display: "grid", gridTemplateColumns: "160px 1fr", rowGap: 8 };
const pill: React.CSSProperties = { background: "var(--border)", borderRadius: 999, padding: "2px 8px", fontSize: 12 };
