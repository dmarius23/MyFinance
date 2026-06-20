import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { taxPaymentsApi, DECLARATION_TYPES, type TaxPaymentRow } from "../api/taxes";
import { documentsApi } from "../api/documents";
import { ApiError } from "../lib/apiClient";
import { MonthBar } from "../components/MonthBar";
import { TaxPaymentModal } from "../components/TaxPaymentModal";

const money = (n: number) => n.toLocaleString("ro-RO");
const dmy = (iso: string) => new Date(iso).toLocaleDateString("ro-RO");

/** MOD-07 — monthly Tax & payments list: a row per company, a column per declaration, email status. */
export function TaxPayments() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [period, setPeriod] = useState(() => new Date().toISOString().slice(0, 7) + "-01");
  const [openFor, setOpenFor] = useState<{ id: string; name: string } | null>(null);
  const [uploadFor, setUploadFor] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const { data, isLoading, error: loadErr } = useQuery({
    queryKey: ["tax-list", period],
    queryFn: () => taxPaymentsApi.list(period),
  });

  const upload = useMutation({
    mutationFn: ({ companyId, file }: { companyId: string; file: File }) =>
      documentsApi.upload(companyId, period, file),
    onSuccess: () => {
      setError(null);
      void qc.invalidateQueries({ queryKey: ["tax-list", period] });
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Upload failed"),
  });

  const pickUpload = (companyId: string) => { setUploadFor(companyId); fileRef.current?.click(); };
  const onFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file && uploadFor) upload.mutate({ companyId: uploadFor, file });
    e.target.value = "";
  };

  const cellFor = (row: TaxPaymentRow, type: string) => row.declarations.find((d) => d.type === type);

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="card">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h1 style={{ marginTop: 0 }}>{t("taxes.title")}</h1>
          <MonthBar value={period} onChange={setPeriod} />
        </div>
      </div>

      <div className="card">
        {isLoading && <p>{t("common.loading")}</p>}
        {loadErr && <p style={{ color: "#dc2626" }}>{loadErr instanceof ApiError ? loadErr.message : "Failed to load"}</p>}
        {error && <p style={{ color: "#dc2626" }}>{error}</p>}
        <input ref={fileRef} type="file" accept="application/pdf" onChange={onFile} style={{ display: "none" }} />

        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8 }}>{t("documents.company")}</th>
              {DECLARATION_TYPES.map((type) => <th key={type} style={{ padding: 8, textAlign: "center" }}>{type}</th>)}
              <th style={{ padding: 8 }}>{t("taxes.emailStatus")}</th>
              <th style={{ padding: 8, textAlign: "right" }}>{t("statements.actions")}</th>
            </tr>
          </thead>
          <tbody>
            {(data ?? []).map((row) => (
              <tr key={row.companyId} style={{ borderTop: "1px solid var(--border)" }}>
                <td style={{ padding: 8 }}>
                  <b>{row.companyName}</b>
                  <div style={{ color: "var(--text-muted)", fontSize: 12 }}>
                    {row.cui}{row.residence ? ` · ${row.residence}` : ""}
                  </div>
                </td>
                {DECLARATION_TYPES.map((type) => {
                  const c = cellFor(row, type);
                  return (
                    <td key={type} style={{ padding: 8, textAlign: "center" }}>
                      {c
                        ? <span title={c.mismatch ? t("taxes.mismatch") : ""}>
                            {money(c.amount)}{c.mismatch ? " ⚠" : ""}
                          </span>
                        : <span style={{ color: "var(--text-muted)" }}>—</span>}
                    </td>
                  );
                })}
                <td style={{ padding: 8 }}>
                  {row.lastEmailAt
                    ? <span style={{ color: "#166534", fontSize: 13 }}>
                        ✓ {dmy(row.lastEmailAt)}{row.emailCount > 1 ? ` (${row.emailCount})` : ""}
                      </span>
                    : <span style={{ color: "var(--text-muted)", fontSize: 13 }}>{t("taxes.notSent")}</span>}
                </td>
                <td style={{ padding: 8, textAlign: "right", whiteSpace: "nowrap" }}>
                  <button style={iconBtn} title={t("taxes.uploadDeclaration")}
                    disabled={upload.isPending}
                    onClick={() => pickUpload(row.companyId)}>📤</button>
                  <button style={iconBtn} title={t("taxes.sendEmail")}
                    disabled={row.declarations.length === 0}
                    onClick={() => setOpenFor({ id: row.companyId, name: row.companyName })}>✉</button>
                </td>
              </tr>
            ))}
            {data && data.length === 0 && (
              <tr><td colSpan={DECLARATION_TYPES.length + 3} style={{ padding: 8, color: "var(--text-muted)" }}>
                {t("taxes.noCompanies")}
              </td></tr>
            )}
          </tbody>
        </table>
      </div>

      {openFor && (
        <TaxPaymentModal companyId={openFor.id} companyName={openFor.name} period={period}
          onClose={() => { setOpenFor(null); void qc.invalidateQueries({ queryKey: ["tax-list", period] }); }} />
      )}
    </div>
  );
}

const iconBtn: React.CSSProperties = {
  background: "none", border: "1px solid var(--border)", borderRadius: 8,
  cursor: "pointer", fontSize: 15, lineHeight: 1, padding: "5px 8px", marginLeft: 6,
};
