import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { settingsApi } from "../api/settings";
import { ApiError } from "../lib/apiClient";
import { ROMANIAN_COUNTIES } from "../domain/counties";
import { TAX_TYPES, taxTypeKey } from "../domain/taxTypes";
import { Field } from "../components/Field";

/** Tenant-level general settings: VAT rate + county treasury-account registry. TENANT_ADMIN only. */
export function Settings() {
  const { t } = useTranslation();
  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="card">
        <h1 style={{ marginTop: 0 }}>{t("nav.settings")}</h1>
      </div>
      <VatRateSection />
      <CountyTreasurySection />
    </div>
  );
}

function VatRateSection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({ queryKey: ["settings"], queryFn: settingsApi.get });
  const [editing, setEditing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const displayed = editing ?? String(data?.vatRate ?? "21");

  const save = useMutation({
    mutationFn: () => settingsApi.updateVatRate(parseFloat(displayed)),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["settings"] });
      setEditing(null);
      setError(null);
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Failed to save"),
  });

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("settings.vat")}</h2>
      {isLoading ? (
        <p>{t("common.loading")}</p>
      ) : (
        <form
          onSubmit={(e) => { e.preventDefault(); setError(null); save.mutate(); }}
          style={{ display: "flex", alignItems: "flex-end", gap: 12 }}
        >
          <Field label={t("settings.vatRate")}>
            <input
              type="number"
              min="0"
              max="100"
              step="0.01"
              required
              value={displayed}
              onChange={(e) => setEditing(e.target.value)}
              style={{ maxWidth: 100 }}
            />
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

function CountyTreasurySection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data: accounts = [], isLoading } = useQuery({
    queryKey: ["county-treasury"],
    queryFn: settingsApi.listTreasury,
  });
  const [form, setForm] = useState({ county: "", taxType: "", iban: "", label: "" });
  const [error, setError] = useState<string | null>(null);

  const add = useMutation({
    mutationFn: () => settingsApi.addTreasury(form),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["county-treasury"] });
      setForm({ county: "", taxType: "", iban: "", label: "" });
      setError(null);
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Failed to add account"),
  });

  const remove = useMutation({
    mutationFn: (id: string) => settingsApi.deleteTreasury(id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["county-treasury"] }),
  });

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("settings.treasury")}</h2>
      {isLoading ? (
        <p>{t("common.loading")}</p>
      ) : (
        <>
          <table style={{ width: "100%", borderCollapse: "collapse", marginBottom: 16 }}>
            <thead>
              <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
                <th style={{ padding: 8 }}>{t("settings.county")}</th>
                <th style={{ padding: 8 }}>{t("settings.taxType")}</th>
                <th style={{ padding: 8 }}>IBAN</th>
                <th style={{ padding: 8 }}>Label</th>
                <th style={{ padding: 8 }} />
              </tr>
            </thead>
            <tbody>
              {accounts.map((a) => (
                <tr key={a.id} style={{ borderTop: "1px solid var(--border)" }}>
                  <td style={{ padding: 8 }}>{a.county}</td>
                  <td style={{ padding: 8 }}>{t(taxTypeKey(a.taxType), { defaultValue: a.taxType })}</td>
                  <td style={{ padding: 8, fontFamily: "monospace" }}>{a.iban}</td>
                  <td style={{ padding: 8 }}>{a.label ?? "—"}</td>
                  <td style={{ padding: 8 }}>
                    <button
                      onClick={() => remove.mutate(a.id)}
                      disabled={remove.isPending}
                      style={{ color: "#dc2626", border: "none", background: "none", cursor: "pointer", padding: "0 4px" }}
                    >
                      ✕
                    </button>
                  </td>
                </tr>
              ))}
              {accounts.length === 0 && (
                <tr>
                  <td colSpan={5} style={{ padding: 8, color: "var(--text-muted)" }}>
                    No treasury accounts configured yet.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          {error && <p style={{ color: "#dc2626" }}>{error}</p>}
          <form
            style={{ display: "flex", gap: 8, flexWrap: "wrap" }}
            onSubmit={(e) => { e.preventDefault(); add.mutate(); }}
          >
            <select
              required
              value={form.county}
              onChange={(e) => setForm({ ...form, county: e.target.value })}
              style={{ flex: "1 1 150px" }}
            >
              <option value="">{t("settings.county")}…</option>
              {ROMANIAN_COUNTIES.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
            <select
              required
              value={form.taxType}
              onChange={(e) => setForm({ ...form, taxType: e.target.value })}
              style={{ flex: "1 1 150px" }}
            >
              <option value="">{t("settings.taxType")}…</option>
              {TAX_TYPES.map((tt) => (
                <option key={tt} value={tt}>{t(taxTypeKey(tt))}</option>
              ))}
            </select>
            <input
              required
              placeholder="IBAN"
              value={form.iban}
              onChange={(e) => setForm({ ...form, iban: e.target.value })}
              style={{ flex: "2 1 200px" }}
            />
            <input
              placeholder="Label"
              value={form.label}
              onChange={(e) => setForm({ ...form, label: e.target.value })}
              style={{ flex: "1 1 100px" }}
            />
            <button className="primary" type="submit" disabled={add.isPending}>
              {add.isPending ? "Adding…" : "Add"}
            </button>
          </form>
        </>
      )}
    </div>
  );
}
