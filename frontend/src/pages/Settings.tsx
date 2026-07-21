import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { settingsApi, type TreasuryAccount, type TreasuryIbans } from "../api/settings";
import { ApiError } from "../lib/apiClient";
import { Field } from "../components/Field";

/** Treasury IBAN columns, in the requested order: CAM, impozite, CASS, CAS, TVA. */
const IBAN_COLS = [
  { key: "ibanCam", label: "CAM" },
  { key: "ibanImpozite", labelKey: "settings.col.impozite" },
  { key: "ibanCass", label: "CASS" },
  { key: "ibanCas", label: "CAS" },
  { key: "ibanTva", label: "TVA" },
] as const satisfies ReadonlyArray<{ key: keyof TreasuryIbans; label?: string; labelKey?: string }>;

const hint: React.CSSProperties = { color: "var(--text-muted)", fontSize: 13, marginTop: 0 };

/**
 * Tenant-level settings. Tax rates + treasury accounts are GLOBAL (SUPER_ADMIN-managed) and shown
 * read-only here; only the firm's sender email is editable per-tenant.
 */
export function Settings() {
  const { t } = useTranslation();
  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="card">
        <h1 style={{ marginTop: 0 }}>{t("nav.settings")}</h1>
      </div>
      <SenderEmailSection />
      <RatesSection />
      <TreasurySection />
    </div>
  );
}

function SenderEmailSection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({ queryKey: ["settings"], queryFn: settingsApi.get });
  const [value, setValue] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const email = value ?? data?.senderEmail ?? "";

  const save = useMutation({
    mutationFn: () => settingsApi.updateSenderEmail(email || null),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["settings"] });
      setValue(null);
      setError(null);
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Failed to save"),
  });

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("settings.senderEmail")}</h2>
      {isLoading ? (
        <p>{t("common.loading")}</p>
      ) : (
        <form
          onSubmit={(e) => { e.preventDefault(); setError(null); save.mutate(); }}
          style={{ display: "flex", alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}
        >
          <Field label={t("settings.senderEmail")}>
            <input type="email" placeholder="contact@firma-contabila.ro" value={email}
              onChange={(e) => setValue(e.target.value)} style={{ minWidth: 240 }} />
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

function RatesSection() {
  const { t } = useTranslation();
  const { data, isLoading } = useQuery({ queryKey: ["settings"], queryFn: settingsApi.get });
  const pct = (v: number | null | undefined) => (v == null ? "—" : `${v}%`);

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("settings.taxRates")}</h2>
      <p style={hint}>{t("settings.managedCentrally")}</p>
      {isLoading ? (
        <p>{t("common.loading")}</p>
      ) : (
        <div style={{ display: "flex", gap: 32, flexWrap: "wrap" }}>
          <Metric label={t("settings.vatRate")} value={pct(data?.vatRate)} />
          <Metric label={t("settings.microRate")} value={pct(data?.microRate)} />
          <Metric label={t("settings.profitRate")} value={pct(data?.profitRate)} />
        </div>
      )}
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div style={{ color: "var(--text-muted)", fontSize: 13 }}>{label}</div>
      <div style={{ fontSize: 22, fontWeight: 600 }}>{value}</div>
    </div>
  );
}

function TreasurySection() {
  const { t } = useTranslation();
  const { data: accounts = [], isLoading } = useQuery({
    queryKey: ["treasury"],
    queryFn: settingsApi.listTreasury,
  });

  const colLabel = (col: (typeof IBAN_COLS)[number]) => ("labelKey" in col ? t(col.labelKey) : col.label);
  const cell = (v: string | null) =>
    v ? <span style={{ fontFamily: "var(--mono)", fontSize: 12 }}>{v}</span> : <span style={{ color: "var(--text-muted)" }}>—</span>;

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("settings.treasury")}</h2>
      <p style={hint}>{t("settings.managedCentrally")}</p>
      {isLoading ? (
        <p>{t("common.loading")}</p>
      ) : (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8 }}>{t("settings.residence")}</th>
              <th style={{ padding: 8 }}>{t("settings.validFrom")}</th>
              {IBAN_COLS.map((col) => <th key={col.key} style={{ padding: 8 }}>{colLabel(col)}</th>)}
            </tr>
          </thead>
          <tbody>
            {accounts.map((a: TreasuryAccount) => (
              <tr key={`${a.residence}-${a.validFrom}`} style={{ borderTop: "1px solid var(--border)" }}>
                <td style={{ padding: 8, whiteSpace: "nowrap" }}><b>{a.residence}</b></td>
                <td style={{ padding: 8, whiteSpace: "nowrap" }}>{a.validFrom}</td>
                {IBAN_COLS.map((col) => <td key={col.key} style={{ padding: 8 }}>{cell(a[col.key])}</td>)}
              </tr>
            ))}
            {accounts.length === 0 && (
              <tr>
                <td colSpan={IBAN_COLS.length + 2} style={{ padding: 8, color: "var(--text-muted)" }}>
                  {t("settings.noTreasury")}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      )}
    </div>
  );
}
