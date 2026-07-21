import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  referenceApi,
  TAX_RATE_CATEGORIES,
  type PlatformTaxRate,
  type PlatformTreasuryAccount,
  type TaxRateCategory,
} from "../api/reference";
import type { TreasuryIbans } from "../api/settings";
import { ApiError } from "../lib/apiClient";
import { ROMANIAN_LOCALITIES } from "../domain/localities";
import { Field } from "../components/Field";

/** Treasury IBAN columns, in the requested order: CAM, impozite, CASS, CAS, TVA. */
const IBAN_COLS = [
  { key: "ibanCam", label: "CAM" },
  { key: "ibanImpozite", labelKey: "settings.col.impozite" },
  { key: "ibanCass", label: "CASS" },
  { key: "ibanCas", label: "CAS" },
  { key: "ibanTva", label: "TVA" },
] as const satisfies ReadonlyArray<{ key: keyof TreasuryIbans; label?: string; labelKey?: string }>;

function ibansOf(a: PlatformTreasuryAccount): TreasuryIbans {
  return { ibanCam: a.ibanCam, ibanImpozite: a.ibanImpozite, ibanCass: a.ibanCass, ibanCas: a.ibanCas, ibanTva: a.ibanTva };
}

const ibanInput: React.CSSProperties = { width: "100%", fontFamily: "var(--mono)", fontSize: 12, boxSizing: "border-box" };

/**
 * SUPER_ADMIN screen for the GLOBAL, effective-dated reference data (national tax rates + treasury
 * accounts). Every tenant reads these; only the platform admin edits them here.
 */
export function AdminReference() {
  const { t } = useTranslation();
  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="card">
        <h1 style={{ marginTop: 0 }}>{t("adminRef.title")}</h1>
        <p style={{ color: "var(--text-muted)", marginBottom: 0 }}>{t("adminRef.intro")}</p>
      </div>
      <TaxRatesSection />
      <TreasurySection />
    </div>
  );
}

/**
 * The three national tax rates (VAT / micro / profit) are a fixed set — this section only edits
 * their current values (no add/delete). Editing updates the value in place so every tenant and every
 * period sees the new rate.
 */
function TaxRatesSection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data: rates = [], isLoading } = useQuery({ queryKey: ["ref-rates"], queryFn: referenceApi.listRates });

  // The current row per category (latest effective date, if more than one ever exists).
  const current = new Map<TaxRateCategory, PlatformTaxRate>();
  for (const r of rates) {
    const existing = current.get(r.category);
    if (!existing || r.validFrom > existing.validFrom) current.set(r.category, r);
  }

  const [edits, setEdits] = useState<Partial<Record<TaxRateCategory, string>>>({});
  const [error, setError] = useState<string | null>(null);
  const valueFor = (cat: TaxRateCategory) => edits[cat] ?? current.get(cat)?.rate?.toString() ?? "";

  const save = useMutation({
    mutationFn: async () => {
      for (const cat of TAX_RATE_CATEGORIES) {
        const row = current.get(cat);
        const raw = edits[cat];
        if (row && raw !== undefined && raw.trim() !== "" && parseFloat(raw) !== row.rate) {
          await referenceApi.updateRate(row.id, parseFloat(raw));
        }
      }
    },
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ["ref-rates"] }); setEdits({}); setError(null); },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Failed to save"),
  });

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("adminRef.taxRates")}</h2>
      {isLoading ? (
        <p>{t("common.loading")}</p>
      ) : (
        <form
          onSubmit={(e) => { e.preventDefault(); setError(null); save.mutate(); }}
          style={{ display: "flex", alignItems: "flex-end", gap: 24, flexWrap: "wrap" }}
        >
          {TAX_RATE_CATEGORIES.map((cat) => (
            <Field key={cat} label={`${t(`taxRate.${cat}`, { defaultValue: cat })} (%)`}>
              <input type="number" min="0" max="100" step="0.01"
                value={valueFor(cat)} disabled={!current.get(cat)}
                onChange={(e) => setEdits({ ...edits, [cat]: e.target.value })}
                style={{ maxWidth: 110 }} />
            </Field>
          ))}
          <button className="primary" type="submit" disabled={save.isPending} style={{ marginBottom: 10 }}>
            {save.isPending ? "Saving…" : t("common.save")}
          </button>
        </form>
      )}
      {error && <p style={{ color: "#dc2626" }}>{error}</p>}
    </div>
  );
}

/** A residence/effective-date row with five inline-editable IBANs (saved on blur) and a delete button. */
function TreasuryRow({ account }: { account: PlatformTreasuryAccount }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [ibans, setIbans] = useState<TreasuryIbans>(ibansOf(account));

  const save = useMutation({
    mutationFn: (next: TreasuryIbans) => referenceApi.updateTreasury(account.id, next),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["ref-treasury"] }),
  });
  const remove = useMutation({
    mutationFn: () => referenceApi.deleteTreasury(account.id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["ref-treasury"] }),
  });

  const saveIfChanged = (key: keyof TreasuryIbans) => {
    if ((ibans[key] ?? "") !== (account[key] ?? "")) save.mutate(ibans);
  };

  return (
    <tr style={{ borderTop: "1px solid var(--border)" }}>
      <td style={{ padding: 8, whiteSpace: "nowrap" }}><b>{account.residence}</b></td>
      <td style={{ padding: 8, whiteSpace: "nowrap" }}>{account.validFrom}</td>
      {IBAN_COLS.map((col) => (
        <td key={col.key} style={{ padding: 4 }}>
          <input value={ibans[col.key] ?? ""} placeholder="—"
            onChange={(e) => setIbans({ ...ibans, [col.key]: e.target.value })}
            onBlur={() => saveIfChanged(col.key)} style={ibanInput} />
        </td>
      ))}
      <td style={{ padding: 8 }}>
        <button onClick={() => remove.mutate()} disabled={remove.isPending} title={t("common.delete")}
          style={{ color: "#dc2626", border: "none", background: "none", cursor: "pointer", padding: "0 4px" }}>✕</button>
      </td>
    </tr>
  );
}

function TreasurySection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data: accounts = [], isLoading } = useQuery({
    queryKey: ["ref-treasury"],
    queryFn: referenceApi.listTreasury,
  });
  const empty = { residence: "", validFrom: "", ibanCam: "", ibanImpozite: "", ibanCass: "", ibanCas: "", ibanTva: "" };
  const [form, setForm] = useState(empty);
  const [error, setError] = useState<string | null>(null);

  const add = useMutation({
    mutationFn: () => referenceApi.addTreasury({
      residence: form.residence,
      validFrom: form.validFrom,
      ibanCam: form.ibanCam || null,
      ibanImpozite: form.ibanImpozite || null,
      ibanCass: form.ibanCass || null,
      ibanCas: form.ibanCas || null,
      ibanTva: form.ibanTva || null,
    }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["ref-treasury"] });
      setForm(empty);
      setError(null);
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Failed to add residence"),
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!form.residence.trim() || !form.validFrom) {
      setError(t("adminRef.residenceAndDateRequired"));
      return;
    }
    add.mutate();
  };

  const colLabel = (col: (typeof IBAN_COLS)[number]) => ("labelKey" in col ? t(col.labelKey) : col.label);

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("adminRef.treasury")}</h2>
      <datalist id="ro-localities-adminref">
        {ROMANIAN_LOCALITIES.map((l) => <option key={l} value={l} />)}
      </datalist>
      {isLoading ? (
        <p>{t("common.loading")}</p>
      ) : (
        <>
          <table style={{ width: "100%", borderCollapse: "collapse", marginBottom: 16 }}>
            <thead>
              <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
                <th style={{ padding: 8 }}>{t("settings.residence")}</th>
                <th style={{ padding: 8 }}>{t("adminRef.validFrom")}</th>
                {IBAN_COLS.map((col) => <th key={col.key} style={{ padding: 8 }}>{colLabel(col)}</th>)}
                <th style={{ padding: 8 }} />
              </tr>
            </thead>
            <tbody>
              {accounts.map((a) => <TreasuryRow key={a.id} account={a} />)}
              {accounts.length === 0 && (
                <tr>
                  <td colSpan={IBAN_COLS.length + 3} style={{ padding: 8, color: "var(--text-muted)" }}>
                    {t("settings.noTreasury")}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          {error && <p style={{ color: "#dc2626" }}>{error}</p>}
          <form onSubmit={submit}>
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <tbody>
                <tr>
                  <td style={{ padding: 4 }}>
                    <input required list="ro-localities-adminref" placeholder={t("settings.residence")}
                      value={form.residence} onChange={(e) => setForm({ ...form, residence: e.target.value })}
                      style={{ width: "100%", boxSizing: "border-box" }} />
                  </td>
                  <td style={{ padding: 4 }}>
                    <input type="date" required value={form.validFrom}
                      onChange={(e) => setForm({ ...form, validFrom: e.target.value })}
                      style={{ width: "100%", boxSizing: "border-box" }} />
                  </td>
                  {IBAN_COLS.map((col) => (
                    <td key={col.key} style={{ padding: 4 }}>
                      <input placeholder={colLabel(col)} value={form[col.key]}
                        onChange={(e) => setForm({ ...form, [col.key]: e.target.value })} style={ibanInput} />
                    </td>
                  ))}
                  <td style={{ padding: 4 }}>
                    <button className="primary" type="submit" disabled={add.isPending}>
                      {add.isPending ? "…" : "+"}
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </form>
        </>
      )}
    </div>
  );
}
