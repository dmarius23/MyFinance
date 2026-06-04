import { useState } from "react";
import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { representativesApi } from "../api/representatives";
import { ApiError } from "../lib/apiClient";
import { vatStatusKey } from "../domain/vat";

/** MOD-03 — company detail: general info, treasury accounts, representatives. */
export function CompanyDetail() {
  const { t } = useTranslation();
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
      <div className="card">
        <h1 style={{ marginTop: 0 }}>{c.legalName}</h1>
        <dl style={grid}>
          <Row k="CUI" v={c.cui} />
          <Row k="Entity type" v={c.entityType ?? "—"} />
          <Row k="Locality" v={c.locality ?? "—"} />
          <Row k={t("company.vatStatus")} v={c.vatStatus ? t(vatStatusKey(c.vatStatus), { defaultValue: c.vatStatus }) : "—"} />
          <Row k={t("company.vatPeriod")} v={c.vatPeriod ?? "—"} />
          <Row k="Status" v={c.status} />
        </dl>
      </div>

      <TreasurySection companyId={id} accounts={treasury.data ?? []} />
      <RepresentativesSection companyId={id} />
    </div>
  );
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
