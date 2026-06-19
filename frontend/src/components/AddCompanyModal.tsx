import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { companiesApi, TAX_REGIMES, taxRegimeKey, type CreateCompanyInput } from "../api/companies";
import { ApiError } from "../lib/apiClient";
import { VAT_STATUSES, vatStatusKey } from "../domain/vat";
import { ENTITY_TYPES } from "../domain/company";
import { VAT_PERIODS, vatPeriodKey } from "../domain/company";
import { ROMANIAN_COUNTIES } from "../domain/counties";
import { Field } from "./Field";

export function AddCompanyModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [form, setForm] = useState<CreateCompanyInput>({ legalName: "", cui: "" });
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: () => companiesApi.create(form),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["companies"] });
      onClose();
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Failed to create company"),
  });

  const set =
    (k: keyof CreateCompanyInput) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
      setForm((f) => ({ ...f, [k]: e.target.value }));

  return (
    <div style={overlay} onClick={onClose}>
      <form
        className="card"
        style={{ width: 480, maxHeight: "90vh", overflowY: "auto" }}
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          setError(null);
          mutation.mutate();
        }}
      >
        <h2 style={{ marginTop: 0 }}>Add company</h2>
        <Field label="Legal name *">
          <input required value={form.legalName} onChange={set("legalName")} />
        </Field>
        <Field label="CUI *">
          <input required value={form.cui} onChange={set("cui")} />
        </Field>
        <Field label={t("company.entityType")}>
          <select value={form.entityType ?? ""} onChange={set("entityType")}>
            <option value="">—</option>
            {ENTITY_TYPES.map((v) => <option key={v} value={v}>{v}</option>)}
          </select>
        </Field>
        <Field label={t("company.fiscalResidence")}>
          <select value={form.locality ?? ""} onChange={set("locality")}>
            <option value="">—</option>
            {ROMANIAN_COUNTIES.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
        </Field>
        <Field label={t("company.vatStatus")}>
          <select value={form.vatStatus ?? ""} onChange={set("vatStatus")}>
            <option value="">—</option>
            {VAT_STATUSES.map((v) => (
              <option key={v} value={v}>{t(vatStatusKey(v))}</option>
            ))}
          </select>
        </Field>
        <Field label={t("company.vatPeriod")}>
          <select value={form.vatPeriod ?? ""} onChange={set("vatPeriod")}>
            <option value="">—</option>
            {VAT_PERIODS.map((v) => (
              <option key={v} value={v}>{t(vatPeriodKey(v))}</option>
            ))}
          </select>
        </Field>
        <Field label={t("company.taxRegime")}>
          <select value={form.taxRegime ?? ""} onChange={set("taxRegime")}>
            <option value="">—</option>
            {TAX_REGIMES.map((v) => <option key={v} value={v}>{t(taxRegimeKey(v))}</option>)}
          </select>
        </Field>
        <label style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 10, fontSize: 13 }}>
          <input type="checkbox" style={{ width: "auto" }} checked={form.hasEmployees ?? false}
            onChange={(e) => setForm((f) => ({ ...f, hasEmployees: e.target.checked }))} />
          {t("company.hasEmployees")}
        </label>
        {error && <p style={{ color: "#dc2626" }}>{error}</p>}
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 12 }}>
          <button type="button" onClick={onClose}>Cancel</button>
          <button className="primary" type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? "Saving…" : "Create"}
          </button>
        </div>
      </form>
    </div>
  );
}

const overlay: React.CSSProperties = {
  position: "fixed",
  inset: 0,
  background: "rgba(15,23,42,0.4)",
  display: "grid",
  placeItems: "center",
  zIndex: 50,
};
