import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { companiesApi, TAX_REGIMES, taxRegimeKey, type CreateCompanyInput } from "../api/companies";
import { Icon } from "./Icon";
import { ApiError } from "../lib/apiClient";
import { VAT_STATUSES, vatStatusKey } from "../domain/vat";
import { ENTITY_TYPES, CUI_PATTERN } from "../domain/company";
import { VAT_PERIODS, vatPeriodKey } from "../domain/company";
import { ROMANIAN_LOCALITIES } from "../domain/localities";
import { explicitValidity } from "../lib/validity";
import { Field } from "./Field";

/** SRL / SA / PFA from the legal name's suffix — ANAF returns the form inside the name, not as a field. */
function entityTypeOf(legalName: string | null): string {
  const n = (legalName ?? "").toUpperCase().replace(/[^A-Z]/g, "");
  if (n.endsWith("SRL")) return "SRL";
  if (n.endsWith("SA")) return "SA";
  if (n.includes("PFA") || n.includes("PERSOANAFIZICAAUTORIZATA")) return "PFA";
  return "";
}

export function AddCompanyModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [form, setForm] = useState<CreateCompanyInput>({ legalName: "", cui: "" });
  const [error, setError] = useState<string | null>(null);
  const [lookupNote, setLookupNote] = useState<{ tone: "ok" | "warn"; msg: string } | null>(null);

  // Pull the official record from ANAF and prefill. Only ever FILLS — a value the user already typed is
  // left alone, so the button can't silently overwrite a deliberate correction.
  const lookup = useMutation({
    mutationFn: () => companiesApi.lookup(form.cui),
    onSuccess: (r) => {
      setForm((f) => ({
        ...f,
        legalName: f.legalName?.trim() ? f.legalName : (r.legalName ?? ""),
        // The treasury accounts key on a residence that HAS a treasury; ANAF's own locality is often a
        // commune (Sat Floreşti…) that has none, so prefer the resolved one and keep ANAF's as a fallback.
        locality: f.locality?.trim() ? f.locality : (r.treasuryResidence ?? r.fiscalLocality ?? ""),
        entityType: f.entityType || entityTypeOf(r.legalName),
        vatStatus: f.vatStatus || (r.vatPayer == null ? "" : r.vatPayer ? "VAT_PAYER" : "NON_VAT_PAYER"),
      }));
      setLookupNote(r.inactive
        ? { tone: "warn", msg: t("company.anafInactive") }
        : !r.treasuryResidence
          // Without a treasury residence the tax emails can't show payment accounts — say so now, not later.
          ? { tone: "warn", msg: t("company.anafNoTreasury", { locality: r.fiscalLocality ?? "—" }) }
          : { tone: "ok", msg: t("company.anafFilled") });
    },
    onError: () => setLookupNote({ tone: "warn", msg: t("company.anafNotFound") }),
  });

  const mutation = useMutation({
    mutationFn: () => companiesApi.create(form),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["companies-all"] });
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
        <Field label={`${t("company.legalName")} *`}>
          <input required {...explicitValidity(t("validation.required", { field: t("company.legalName") }))}
            value={form.legalName} onChange={set("legalName")} />
        </Field>
        <Field label="CUI *">
          <div style={{ display: "flex", gap: 6 }}>
            <input required pattern={CUI_PATTERN} title={t("company.cuiInvalid")} style={{ flex: 1, minWidth: 0 }}
              {...explicitValidity(t("company.cuiInvalid"))} value={form.cui}
              onChange={(e) => { setLookupNote(null); set("cui")(e); }} />
            <button type="button" style={{ flex: "none" }} title={t("company.anafLookupHint")}
              disabled={!form.cui.trim() || lookup.isPending}
              onClick={() => { setLookupNote(null); lookup.mutate(); }}>
              <Icon name="search" size={13} style={{ verticalAlign: "-2px", marginRight: 4 }} />
              {lookup.isPending ? "…" : t("company.anafLookup")}
            </button>
          </div>
          {lookupNote && (
            <div style={{ marginTop: 4, fontSize: 11.5,
              color: lookupNote.tone === "ok" ? "var(--ok-fg, #166534)" : "var(--warn-fg, #92400e)" }}>
              {lookupNote.msg}
            </div>
          )}
        </Field>
        <Field label={t("company.entityType")}>
          <select value={form.entityType ?? ""} onChange={set("entityType")}>
            <option value="">—</option>
            {ENTITY_TYPES.map((v) => <option key={v} value={v}>{v}</option>)}
          </select>
        </Field>
        <Field label={`${t("company.fiscalResidence")} *`}>
          <input required list="ro-localities" value={form.locality ?? ""} onChange={set("locality")}
            {...explicitValidity(t("validation.required", { field: t("company.fiscalResidence") }))}
            placeholder={t("company.fiscalResidencePlaceholder")} />
          <datalist id="ro-localities">
            {ROMANIAN_LOCALITIES.map((l) => <option key={l} value={l} />)}
          </datalist>
        </Field>
        <Field label={`${t("company.vatStatus")} *`}>
          <select required value={form.vatStatus ?? ""} onChange={set("vatStatus")}
            {...explicitValidity(t("validation.required", { field: t("company.vatStatus") }))}>
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
        <Field label={`${t("company.taxRegime")} *`}>
          <select required value={form.taxRegime ?? ""} onChange={set("taxRegime")}
            {...explicitValidity(t("validation.required", { field: t("company.taxRegime") }))}>
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
