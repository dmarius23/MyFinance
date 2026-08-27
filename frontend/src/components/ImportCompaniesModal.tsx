import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { companiesApi, type ImportResult, type ImportRowResult } from "../api/companies";
import { ApiError } from "../lib/apiClient";

const TEMPLATE =
  "name;cui;type;residence;vat;tax_regime;has_employees;rep_name;rep_email;rep_phone\n" +
  "ACME SRL;RO12345678;SRL;Cluj-Napoca;platitor;micro;da;Ion Popescu;ion@acme.ro;0712345678\n" +
  "BETA SRL;18547290;SRL;București;neplatitor;profit;nu;;;\n";

/** Bulk-import companies from a CSV (firm staff). Shows a per-row report; partial import. */
export function ImportCompaniesModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const fileRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const run = useMutation({
    mutationFn: () => companiesApi.importCsv(file!),
    onSuccess: (r) => {
      setResult(r);
      void qc.invalidateQueries({ queryKey: ["companies-all"] });
      void qc.invalidateQueries({ queryKey: ["representatives-all"] });
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : t("common.actionFailed")),
  });

  const downloadTemplate = () => {
    const url = URL.createObjectURL(new Blob([TEMPLATE], { type: "text/csv;charset=utf-8" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = "companies-template.csv";
    a.click();
    URL.revokeObjectURL(url);
  };

  const pill = (s: ImportRowResult["status"]) =>
    s === "CREATED" ? "pill round ok" : s === "SKIPPED" ? "pill round muted" : "pill round danger";
  const statusLabel = (s: ImportRowResult["status"]) => t(`companies.importStatus.${s}`);

  return (
    <div style={overlay} onClick={onClose}>
      <div className="card" style={{ width: 640, maxHeight: "90vh", overflowY: "auto" }} onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>{t("companies.importTitle")}</h2>

        {!result && (
          <>
            <p style={{ color: "var(--text-secondary)", fontSize: 13, lineHeight: 1.5 }}>{t("companies.importHint")}</p>
            <button type="button" onClick={downloadTemplate} style={{ marginBottom: 12 }}>{t("companies.importTemplate")}</button>
            <div style={{ marginBottom: 12 }}>
              <input ref={fileRef} type="file" accept=".csv,text/csv"
                onChange={(e) => { setFile(e.target.files?.[0] ?? null); setError(null); }} />
            </div>
            {error && <p style={{ color: "#dc2626" }}>{error}</p>}
            <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
              <button type="button" onClick={onClose}>{t("common.cancel")}</button>
              <button className="primary" type="button" disabled={!file || run.isPending}
                onClick={() => { setError(null); run.mutate(); }}>
                {run.isPending ? t("common.loading") : t("companies.importRun")}
              </button>
            </div>
          </>
        )}

        {result && (
          <>
            <p style={{ fontSize: 14 }}>
              {t("companies.importSummary", { created: result.created, skipped: result.skipped, invalid: result.invalid })}
            </p>
            <div style={{ maxHeight: 360, overflowY: "auto", border: "1px solid var(--hair)", borderRadius: 8 }}>
              <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13 }}>
                <thead>
                  <tr style={{ textAlign: "left", color: "var(--text-muted)", background: "var(--th-bg)" }}>
                    <th style={{ padding: "6px 8px" }}>#</th>
                    <th style={{ padding: "6px 8px" }}>{t("company.legalName")}</th>
                    <th style={{ padding: "6px 8px" }}>{t("company.status")}</th>
                    <th style={{ padding: "6px 8px" }}>{t("companies.importDetail")}</th>
                  </tr>
                </thead>
                <tbody>
                  {result.rows.map((r) => (
                    <tr key={r.line} style={{ borderTop: "1px solid var(--hair)" }}>
                      <td style={{ padding: "6px 8px", color: "var(--text-muted)" }}>{r.line}</td>
                      <td style={{ padding: "6px 8px" }}>{r.name || "—"}</td>
                      <td style={{ padding: "6px 8px" }}><span className={pill(r.status)}>{statusLabel(r.status)}</span></td>
                      <td style={{ padding: "6px 8px", color: "var(--text-secondary)" }}>{r.message ?? ""}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div style={{ display: "flex", justifyContent: "flex-end", marginTop: 12 }}>
              <button className="primary" type="button" onClick={onClose}>{t("common.close")}</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(15,23,42,0.4)",
  display: "grid", placeItems: "center", zIndex: 50,
};
