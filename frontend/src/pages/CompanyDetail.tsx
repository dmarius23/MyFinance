import { useState } from "react";
import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { companiesApi, type Company } from "../api/companies";
import { representativesApi } from "../api/representatives";
import { ApiError } from "../lib/apiClient";
import { VAT_STATUSES, vatStatusKey } from "../domain/vat";
import { Field } from "../components/Field";

/** MOD-03 — company detail: general info, treasury accounts, representatives. */
export function CompanyDetail() {
  const { id = "" } = useParams();
  const company = useQuery({ queryKey: ["company", id], queryFn: () => companiesApi.get(id) });
  const treasury = useQuery({ queryKey: ["treasury", id], queryFn: () => companiesApi.listTreasury(id) });

  if (company.isLoading) return <div className="card">Loading…</div>;
  if (company.error)
    return <div className="card"><p style={{ color: "#dc2626" }}>
      {company.error instanceof ApiError ? company.error.message : "Failed to load company"}</p></div>;

  const c = company.data!;
  return (
    <div style={{ display: "grid", gap: 16 }}>
      <GeneralInfoSection company={c} />
      <TreasurySection companyId={id} accounts={treasury.data ?? []} />
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
            <button onClick={() => { setForm(toForm(company)); setError(null); setEditing(true); }}>Edit</button>
            <button onClick={() => toggleStatus.mutate()} disabled={toggleStatus.isPending}>
              {company.status === "ACTIVE" ? "Deactivate" : "Activate"}
            </button>
          </div>
        </div>
        <dl style={grid}>
          <Row k="CUI" v={company.cui} />
          <Row k="Entity type" v={company.entityType ?? "—"} />
          <Row k="Locality" v={company.locality ?? "—"} />
          <Row k={t("company.vatStatus")} v={company.vatStatus ? t(vatStatusKey(company.vatStatus), { defaultValue: company.vatStatus }) : "—"} />
          <Row k={t("company.vatPeriod")} v={company.vatPeriod ?? "—"} />
          <Row k="Status" v={company.status} />
        </dl>
      </div>
    );
  }

  return (
    <div className="card">
      <h1 style={{ marginTop: 0 }}>Edit company</h1>
      <form onSubmit={(e) => { e.preventDefault(); setError(null); save.mutate(); }}>
        <Field label="Legal name *"><input required value={form.legalName} onChange={(e) => setForm({ ...form, legalName: e.target.value })} /></Field>
        <Field label="CUI"><input value={company.cui} disabled /></Field>
        <Field label="Entity type"><input value={form.entityType} onChange={(e) => setForm({ ...form, entityType: e.target.value })} placeholder="SRL" /></Field>
        <Field label="Locality"><input value={form.locality} onChange={(e) => setForm({ ...form, locality: e.target.value })} /></Field>
        <Field label={t("company.vatStatus")}>
          <select value={form.vatStatus} onChange={(e) => setForm({ ...form, vatStatus: e.target.value })}>
            <option value="">—</option>
            {VAT_STATUSES.map((v) => (
              <option key={v} value={v}>{t(vatStatusKey(v))}</option>
            ))}
          </select>
        </Field>
        <Field label={t("company.vatPeriod")}><input value={form.vatPeriod} onChange={(e) => setForm({ ...form, vatPeriod: e.target.value })} placeholder="MONTHLY" /></Field>
        {error && <p style={{ color: "#dc2626" }}>{error}</p>}
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
          <button type="button" onClick={() => setEditing(false)}>Cancel</button>
          <button className="primary" type="submit" disabled={save.isPending}>
            {save.isPending ? "Saving…" : "Save"}
          </button>
        </div>
      </form>
    </div>
  );
}

function toForm(c: Company) {
  return {
    legalName: c.legalName,
    entityType: c.entityType ?? "",
    locality: c.locality ?? "",
    vatStatus: c.vatStatus ?? "",
    vatPeriod: c.vatPeriod ?? "",
  };
}

function TreasurySection({ companyId, accounts }: { companyId: string; accounts: { id: string; taxType: string; iban: string; label: string | null }[] }) {
  const qc = useQueryClient();
  const [form, setForm] = useState({ taxType: "", locality: "", iban: "", label: "" });
  const add = useMutation({
    mutationFn: () => companiesApi.addTreasury(companyId, form),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["treasury", companyId] });
      setForm({ taxType: "", locality: "", iban: "", label: "" });
    },
  });
  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>Treasury accounts</h2>
      <ul>
        {accounts.map((a) => (
          <li key={a.id}>{a.taxType} — {a.iban} {a.label ? `(${a.label})` : ""}</li>
        ))}
        {accounts.length === 0 && <li style={{ color: "var(--text-muted)" }}>None yet</li>}
      </ul>
      <form style={{ display: "flex", gap: 8, marginTop: 8 }} onSubmit={(e) => { e.preventDefault(); add.mutate(); }}>
        <input placeholder="Tax type" required value={form.taxType} onChange={(e) => setForm({ ...form, taxType: e.target.value })} />
        <input placeholder="Locality" value={form.locality} onChange={(e) => setForm({ ...form, locality: e.target.value })} />
        <input placeholder="IBAN" required value={form.iban} onChange={(e) => setForm({ ...form, iban: e.target.value })} />
        <input placeholder="Label" value={form.label} onChange={(e) => setForm({ ...form, label: e.target.value })} />
        <button className="primary" type="submit" disabled={add.isPending}>Add</button>
      </form>
    </div>
  );
}

function RepresentativesSection({ companyId }: { companyId: string }) {
  const qc = useQueryClient();
  const reps = useQuery({ queryKey: ["reps", companyId], queryFn: () => representativesApi.list(companyId) });
  const [form, setForm] = useState({ email: "", name: "" });
  const [error, setError] = useState<string | null>(null);
  const invite = useMutation({
    mutationFn: () => representativesApi.invite(companyId, form),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["reps", companyId] });
      setForm({ email: "", name: "" });
      setError(null);
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Invite failed"),
  });
  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>Representatives</h2>
      {reps.isLoading && <p>Loading…</p>}
      <ul>
        {(reps.data ?? []).map((r) => (
          <li key={r.id}>{r.name ?? r.email} — {r.email} <span style={pill}>{r.status}</span></li>
        ))}
        {reps.data?.length === 0 && <li style={{ color: "var(--text-muted)" }}>No representatives yet</li>}
      </ul>
      {error && <p style={{ color: "#dc2626" }}>{error}</p>}
      <form style={{ display: "flex", gap: 8, marginTop: 8 }} onSubmit={(e) => { e.preventDefault(); invite.mutate(); }}>
        <input type="email" placeholder="email" required value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
        <input placeholder="name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
        <button className="primary" type="submit" disabled={invite.isPending}>Invite</button>
      </form>
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (<>
    <dt style={{ color: "var(--text-muted)" }}>{k}</dt>
    <dd style={{ margin: 0 }}>{v}</dd>
  </>);
}

const grid: React.CSSProperties = { display: "grid", gridTemplateColumns: "160px 1fr", rowGap: 8 };
const pill: React.CSSProperties = { background: "var(--border)", borderRadius: 999, padding: "2px 8px", fontSize: 12 };
