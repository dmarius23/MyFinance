import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { companiesApi, TAX_REGIMES, taxRegimeKey, type Company } from "../api/companies";
import { representativesApi } from "../api/representatives";
import { ApiError } from "../lib/apiClient";
import { VAT_STATUSES, vatStatusKey } from "../domain/vat";
import { ENTITY_TYPES, VAT_PERIODS, vatPeriodKey } from "../domain/company";
import { ROMANIAN_LOCALITIES } from "../domain/localities";
import { Field } from "../components/Field";
import { Icon } from "../components/Icon";

const EDIT_FORM_ID = "company-edit-form";

/** Client company detail: general info (view/edit), representatives. */
export function CompanyDetail() {
  const { t } = useTranslation();
  const { id = "" } = useParams();
  const company = useQuery({ queryKey: ["company", id], queryFn: () => companiesApi.get(id) });

  if (company.isLoading) return <div className="card">{t("common.loading")}</div>;
  if (company.error)
    return (
      <div className="card">
        <p style={{ color: "#dc2626" }}>
          {company.error instanceof ApiError ? company.error.message : t("companies.loadError")}
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
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState(() => toForm(company));

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ["company", company.id] });
    void qc.invalidateQueries({ queryKey: ["companies-all"] });
  };

  const save = useMutation({
    mutationFn: () => companiesApi.update(company.id, form),
    onSuccess: () => { invalidate(); setEditing(false); },
    onError: (e) => setError(e instanceof ApiError ? e.message : t("common.saveError")),
  });

  const toggleStatus = useMutation({
    mutationFn: () =>
      companiesApi.setStatus(company.id, company.status === "ACTIVE" ? "INACTIVE" : "ACTIVE"),
    onSuccess: invalidate,
  });

  return (
    <div className="card">
      {/* Header — the same button slot in both modes: Edit/Deactivate in view, Cancel/Save in edit. */}
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 12 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 12, minWidth: 0 }}>
          <button onClick={() => navigate("/companies")} title={t("companies.back")}
            style={{ ...iconBtn, width: 34, height: 34 }}><Icon name="chevronLeft" size={18} /></button>
          <h1 style={{ marginTop: 0, marginBottom: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{company.legalName}</h1>
        </div>
        <div style={{ display: "flex", gap: 8 }}>
          {editing ? (
            <>
              <button type="button" onClick={() => { setEditing(false); setError(null); }}>{t("common.cancel")}</button>
              <button className="primary" type="submit" form={EDIT_FORM_ID} disabled={save.isPending}>
                {save.isPending ? t("common.saving") : t("common.save")}
              </button>
            </>
          ) : (
            <>
              <button onClick={() => { setForm(toForm(company)); setError(null); setEditing(true); }}>{t("common.edit")}</button>
              <button onClick={() => toggleStatus.mutate()} disabled={toggleStatus.isPending}>
                {company.status === "ACTIVE" ? t("company.deactivate") : t("company.activate")}
              </button>
            </>
          )}
        </div>
      </div>

      {!editing ? (
        <dl style={grid}>
          <Row k={t("company.cui")} v={company.cui} />
          <Row k={t("company.entityType")} v={company.entityType ?? "—"} />
          <Row k={t("company.fiscalResidence")} v={company.locality ?? "—"} />
          <Row k={t("company.vatStatus")} v={company.vatStatus ? t(vatStatusKey(company.vatStatus), { defaultValue: company.vatStatus }) : "—"} />
          <Row k={t("company.vatPeriod")} v={company.vatPeriod ? t(vatPeriodKey(company.vatPeriod), { defaultValue: company.vatPeriod }) : "—"} />
          <Row k={t("company.taxRegime")} v={company.taxRegime ? t(taxRegimeKey(company.taxRegime), { defaultValue: company.taxRegime }) : "—"} />
          <Row k={t("company.hasEmployees")} v={company.hasEmployees == null ? "—" : t(company.hasEmployees ? "common.yes" : "common.no")} />
          <Row k={t("company.status")} v={t(`companyStatus.${company.status}`, { defaultValue: company.status })} />
        </dl>
      ) : (
        <form id={EDIT_FORM_ID} style={{ marginTop: 12 }}
          onSubmit={(e) => { e.preventDefault(); setError(null); save.mutate(); }}>
          <Field label={`${t("company.legalName")} *`}>
            <input required value={form.legalName} onChange={(e) => setForm({ ...form, legalName: e.target.value })} />
          </Field>
          <Field label={`${t("company.cui")} *`}>
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
              {VAT_STATUSES.map((v) => <option key={v} value={v}>{t(vatStatusKey(v))}</option>)}
            </select>
          </Field>
          <Field label={t("company.vatPeriod")}>
            <select value={form.vatPeriod} onChange={(e) => setForm({ ...form, vatPeriod: e.target.value })}>
              <option value="">—</option>
              {VAT_PERIODS.map((v) => <option key={v} value={v}>{t(vatPeriodKey(v))}</option>)}
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
        </form>
      )}
    </div>
  );
}

const REP_GRID = "minmax(120px,1.3fr) minmax(160px,1.6fr) minmax(110px,1fr) 90px auto";

function RepresentativesSection({ companyId }: { companyId: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const reps = useQuery({ queryKey: ["reps", companyId], queryFn: () => representativesApi.list(companyId) });
  const [form, setForm] = useState({ name: "", email: "", phone: "" });
  const [editing, setEditing] = useState<{ id: string; name: string; email: string; phone: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const onErr = (e: unknown) => setError(e instanceof ApiError ? e.message : t("common.actionFailed"));
  const refresh = () => { void qc.invalidateQueries({ queryKey: ["reps", companyId] }); setError(null); };
  const invite = useMutation({
    mutationFn: () => representativesApi.invite(companyId, { name: form.name, email: form.email, phone: form.phone || undefined }),
    onSuccess: () => { refresh(); setForm({ name: "", email: "", phone: "" }); },
    onError: onErr,
  });
  const update = useMutation({
    mutationFn: () => representativesApi.update(companyId, editing!.id,
      { name: editing!.name, phone: editing!.phone || undefined, email: editing!.email || undefined }),
    onSuccess: () => { refresh(); setEditing(null); },
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

  const inp: React.CSSProperties = { width: "100%", minWidth: 0 };
  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("company.representatives")}</h2>
      {reps.isLoading && <p>{t("common.loading")}</p>}

      <div style={{ display: "grid", gap: 6 }}>
        {(reps.data ?? []).map((r) => (
          <div key={r.id} style={{ display: "grid", gridTemplateColumns: REP_GRID, alignItems: "center", gap: 10,
            padding: "6px 0", borderTop: "1px solid var(--hair)", opacity: r.status === "INACTIVE" ? 0.55 : 1 }}>
            {editing?.id === r.id ? (
              <>
                <input placeholder={t("company.namePlaceholder")} value={editing.name} autoFocus style={inp}
                  onChange={(e) => setEditing({ ...editing, name: e.target.value })} />
                <input type="email" placeholder={t("company.emailPlaceholder")} value={editing.email} style={inp}
                  onChange={(e) => setEditing({ ...editing, email: e.target.value })} />
                <input type="tel" placeholder={t("company.phonePlaceholder")} value={editing.phone} style={inp}
                  onChange={(e) => setEditing({ ...editing, phone: e.target.value })} />
                <span />
                <div style={{ display: "flex", gap: 6, justifyContent: "flex-end" }}>
                  <button className="primary" type="button" disabled={update.isPending || !editing.name.trim() || !editing.email.trim()}
                    onClick={() => update.mutate()}>{t("common.save")}</button>
                  <button type="button" disabled={update.isPending} onClick={() => setEditing(null)}>{t("common.cancel")}</button>
                </div>
              </>
            ) : (
              <>
                <span style={{ fontWeight: 600, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{r.name ?? "—"}</span>
                <span style={{ color: "var(--text-secondary)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{r.email}</span>
                <span style={{ color: "var(--text-muted)" }}>{r.phone ?? "—"}</span>
                <span><span className="pill round" style={{ fontSize: 11 }}>{t(`team.st.${r.status}`, { defaultValue: r.status })}</span></span>
                <div style={{ display: "flex", gap: 6, justifyContent: "flex-end" }}>
                  <button type="button" onClick={() => setEditing({ id: r.id, name: r.name ?? "", email: r.email, phone: r.phone ?? "" })}>
                    {t("common.edit")}
                  </button>
                  <button type="button" disabled={setActive.isPending}
                    onClick={() => setActive.mutate({ userId: r.id, active: r.status === "INACTIVE" })}>
                    {r.status === "INACTIVE" ? t("company.activate") : t("company.deactivate")}
                  </button>
                  <button type="button" style={{ color: "#dc2626" }} disabled={remove.isPending}
                    onClick={() => { if (window.confirm(t("company.removeConfirm", { name: r.name ?? r.email }))) remove.mutate(r.id); }}>
                    {t("company.remove")}
                  </button>
                </div>
              </>
            )}
          </div>
        ))}
        {reps.data?.length === 0 && <div style={{ color: "var(--text-muted)" }}>{t("company.noReps")}</div>}
      </div>

      {error && <p style={{ color: "#dc2626" }}>{error}</p>}

      {/* Invite form — same three fields the edit row now uses. */}
      <form style={{ display: "grid", gridTemplateColumns: REP_GRID, alignItems: "center", gap: 10, marginTop: 12 }}
        onSubmit={(e) => { e.preventDefault(); invite.mutate(); }}>
        <input placeholder={t("company.namePlaceholder")} required value={form.name} style={inp}
          onChange={(e) => setForm({ ...form, name: e.target.value })} />
        <input type="email" placeholder={t("company.emailPlaceholder")} required value={form.email} style={inp}
          onChange={(e) => setForm({ ...form, email: e.target.value })} />
        <input type="tel" placeholder={t("company.phonePlaceholder")} value={form.phone} style={inp}
          onChange={(e) => setForm({ ...form, phone: e.target.value })} />
        <span />
        <div style={{ display: "flex", justifyContent: "flex-end" }}>
          <button className="primary" type="submit" disabled={invite.isPending}>{t("company.invite")}</button>
        </div>
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

const grid: React.CSSProperties = { display: "grid", gridTemplateColumns: "160px 1fr", rowGap: 8, marginTop: 12 };
const iconBtn: React.CSSProperties = { padding: 0, border: "1px solid var(--border)", background: "var(--surface)", borderRadius: 9, display: "flex", alignItems: "center", justifyContent: "center", color: "var(--text-secondary, #55605d)", cursor: "pointer", flex: "none" };
