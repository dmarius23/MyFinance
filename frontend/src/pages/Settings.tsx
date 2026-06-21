import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { settingsApi, type TreasuryAccount, type TreasuryIbans } from "../api/settings";
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

function ibansOf(a: TreasuryAccount): TreasuryIbans {
  return { ibanCam: a.ibanCam, ibanImpozite: a.ibanImpozite, ibanCass: a.ibanCass, ibanCas: a.ibanCas, ibanTva: a.ibanTva };
}

const ibanInput: React.CSSProperties = { width: "100%", fontFamily: "monospace", fontSize: 12, boxSizing: "border-box" };

/** Tenant-level general settings: VAT rate + county treasury-account registry. TENANT_ADMIN only. */
export function Settings() {
  const { t } = useTranslation();
  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="card">
        <h1 style={{ marginTop: 0 }}>{t("nav.settings")}</h1>
      </div>
      <VatRateSection />
      <TreasurySection />
    </div>
  );
}

function VatRateSection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({ queryKey: ["settings"], queryFn: settingsApi.get });
  const [form, setForm] = useState<{ vatRate: string; microRate: string; profitRate: string; senderEmail: string } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const f = form ?? {
    vatRate: String(data?.vatRate ?? "21"),
    microRate: String(data?.microRate ?? "3"),
    profitRate: String(data?.profitRate ?? "16"),
    senderEmail: data?.senderEmail ?? "",
  };
  const setField = (k: "vatRate" | "microRate" | "profitRate" | "senderEmail") =>
    (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...f, [k]: e.target.value });

  const save = useMutation({
    mutationFn: () => settingsApi.updateRates({
      vatRate: parseFloat(f.vatRate), microRate: parseFloat(f.microRate), profitRate: parseFloat(f.profitRate),
      senderEmail: f.senderEmail,
    }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["settings"] });
      setForm(null);
      setError(null);
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Failed to save"),
  });

  const num = (label: string, k: "vatRate" | "microRate" | "profitRate") => (
    <Field label={label}>
      <input type="number" min="0" max="100" step="0.01" required
        value={f[k]} onChange={setField(k)} style={{ maxWidth: 100 }} />
    </Field>
  );

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("settings.taxRates")}</h2>
      {isLoading ? (
        <p>{t("common.loading")}</p>
      ) : (
        <form
          onSubmit={(e) => { e.preventDefault(); setError(null); save.mutate(); }}
          style={{ display: "flex", alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}
        >
          {num(t("settings.vatRate"), "vatRate")}
          {num(t("settings.microRate"), "microRate")}
          {num(t("settings.profitRate"), "profitRate")}
          <Field label={t("settings.senderEmail")}>
            <input type="email" placeholder="contact@firma-contabila.ro" value={f.senderEmail}
              onChange={setField("senderEmail")} style={{ minWidth: 240 }} />
          </Field>
          <button className="primary" type="submit" disabled={save.isPending} style={{ marginBottom: 10 }}>
            {save.isPending ? "Saving…" : t("common.save")}
          </button>
        </form>
      )}
      {error && <p style={{ color: "#dc2626" }}>{error}</p>}
    </div>
  );
}

/** A residence row with five inline-editable IBANs (saved on blur) and a delete button. */
function TreasuryRow({ account }: { account: TreasuryAccount }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [ibans, setIbans] = useState<TreasuryIbans>(ibansOf(account));

  const save = useMutation({
    mutationFn: (next: TreasuryIbans) => settingsApi.updateTreasury(account.id, next),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["treasury"] }),
  });
  const remove = useMutation({
    mutationFn: () => settingsApi.deleteTreasury(account.id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["treasury"] }),
  });

  const saveIfChanged = (key: keyof TreasuryIbans) => {
    if ((ibans[key] ?? "") !== (account[key] ?? "")) save.mutate(ibans);
  };

  return (
    <tr style={{ borderTop: "1px solid var(--border)" }}>
      <td style={{ padding: 8, whiteSpace: "nowrap" }}><b>{account.residence}</b></td>
      {IBAN_COLS.map((col) => (
        <td key={col.key} style={{ padding: 4 }}>
          <input
            value={ibans[col.key] ?? ""}
            placeholder="—"
            onChange={(e) => setIbans({ ...ibans, [col.key]: e.target.value })}
            onBlur={() => saveIfChanged(col.key)}
            style={ibanInput}
          />
        </td>
      ))}
      <td style={{ padding: 8 }}>
        <button onClick={() => remove.mutate()} disabled={remove.isPending}
          title={t("common.delete")}
          style={{ color: "#dc2626", border: "none", background: "none", cursor: "pointer", padding: "0 4px" }}>
          ✕
        </button>
      </td>
    </tr>
  );
}

function TreasurySection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data: accounts = [], isLoading } = useQuery({
    queryKey: ["treasury"],
    queryFn: settingsApi.listTreasury,
  });
  const empty = { residence: "", ibanCam: "", ibanImpozite: "", ibanCass: "", ibanCas: "", ibanTva: "" };
  const [form, setForm] = useState(empty);
  const [error, setError] = useState<string | null>(null);

  const add = useMutation({
    mutationFn: () => settingsApi.addTreasury({
      residence: form.residence,
      ibanCam: form.ibanCam || null,
      ibanImpozite: form.ibanImpozite || null,
      ibanCass: form.ibanCass || null,
      ibanCas: form.ibanCas || null,
      ibanTva: form.ibanTva || null,
    }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["treasury"] });
      setForm(empty);
      setError(null);
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Failed to add residence"),
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!form.residence.trim()) {
      setError(t("settings.residenceRequired"));
      return;
    }
    add.mutate();
  };

  const colLabel = (col: (typeof IBAN_COLS)[number]) =>
    "labelKey" in col ? t(col.labelKey) : col.label;

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("settings.treasury")}</h2>
      <datalist id="ro-localities-settings">
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
                {IBAN_COLS.map((col) => <th key={col.key} style={{ padding: 8 }}>{colLabel(col)}</th>)}
                <th style={{ padding: 8 }} />
              </tr>
            </thead>
            <tbody>
              {accounts.map((a) => <TreasuryRow key={a.id} account={a} />)}
              {accounts.length === 0 && (
                <tr>
                  <td colSpan={IBAN_COLS.length + 2} style={{ padding: 8, color: "var(--text-muted)" }}>
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
                    <input required list="ro-localities-settings" placeholder={t("settings.residence")}
                      value={form.residence} onChange={(e) => setForm({ ...form, residence: e.target.value })}
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
