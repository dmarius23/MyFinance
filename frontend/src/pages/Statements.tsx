import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { documentsApi } from "../api/documents";
import { ApiError } from "../lib/apiClient";
import { Field } from "../components/Field";

/** Statements & invoices — document intake (staff). Upload, list, download, delete. */
export function Statements() {
  const { t } = useTranslation();
  const companies = useQuery({ queryKey: ["companies"], queryFn: companiesApi.list });
  const [companyId, setCompanyId] = useState("");
  const [period, setPeriod] = useState(() => new Date().toISOString().slice(0, 7) + "-01");

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="card">
        <h1 style={{ marginTop: 0 }}>{t("documents.title")}</h1>
        <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
          <Field label={t("documents.company")}>
            <select value={companyId} onChange={(e) => setCompanyId(e.target.value)}>
              <option value="">—</option>
              {(companies.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>{c.legalName}</option>
              ))}
            </select>
          </Field>
          <Field label={t("documents.period")}>
            <input
              type="month"
              value={period.slice(0, 7)}
              onChange={(e) => setPeriod(e.target.value + "-01")}
            />
          </Field>
        </div>
      </div>

      {companyId && <UploadCard companyId={companyId} period={period} />}
      {companyId && <DocumentsTable companyId={companyId} period={period} />}
    </div>
  );
}

function UploadCard({ companyId, period }: { companyId: string; period: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);

  const mutation = useMutation({
    mutationFn: () => documentsApi.upload(companyId, period, file!),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["documents", companyId, period] });
      setFile(null);
      setError(null);
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : "Upload failed"),
  });

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t("documents.upload")}</h2>
      <form
        style={{ display: "flex", gap: 8, alignItems: "center" }}
        onSubmit={(e) => { e.preventDefault(); if (file) mutation.mutate(); }}
      >
        <input
          type="file"
          accept="application/pdf,image/png,image/jpeg,image/webp"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        />
        <button className="primary" type="submit" disabled={!file || mutation.isPending}>
          {mutation.isPending ? "…" : t("documents.upload")}
        </button>
      </form>
      {error && <p style={{ color: "#dc2626" }}>{error}</p>}
    </div>
  );
}

function DocumentsTable({ companyId, period }: { companyId: string; period: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data = [], isLoading } = useQuery({
    queryKey: ["documents", companyId, period],
    queryFn: () => documentsApi.list(companyId, period),
  });

  const remove = useMutation({
    mutationFn: (id: string) => documentsApi.remove(companyId, id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["documents", companyId, period] }),
  });

  const handleDownload = async (id: string, filename: string) => {
    const blob = await documentsApi.download(companyId, id);
    const url = URL.createObjectURL(blob);
    const a = window.document.createElement("a");
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="card">
      {isLoading ? (
        <p>{t("common.loading")}</p>
      ) : (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8 }}>{t("documents.filename")}</th>
              <th style={{ padding: 8 }}>{t("documents.type")}</th>
              <th style={{ padding: 8 }}>{t("documents.uploadedAt")}</th>
              <th style={{ padding: 8 }} />
            </tr>
          </thead>
          <tbody>
            {data.map((d) => (
              <tr key={d.id} style={{ borderTop: "1px solid var(--border)" }}>
                <td style={{ padding: 8 }}>{d.originalFilename}</td>
                <td style={{ padding: 8 }}>{t(`documentType.${d.type}`, { defaultValue: d.type })}</td>
                <td style={{ padding: 8 }}>{new Date(d.uploadedAt).toLocaleString()}</td>
                <td style={{ padding: 8, whiteSpace: "nowrap" }}>
                  <button onClick={() => void handleDownload(d.id, d.originalFilename)}>
                    {t("documents.download")}
                  </button>{" "}
                  <button
                    onClick={() => remove.mutate(d.id)}
                    disabled={remove.isPending}
                    style={{ color: "#dc2626", border: "none", background: "none", cursor: "pointer" }}
                  >
                    ✕
                  </button>
                </td>
              </tr>
            ))}
            {data.length === 0 && (
              <tr>
                <td colSpan={4} style={{ padding: 8, color: "var(--text-muted)" }}>
                  {t("documents.empty")}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      )}
    </div>
  );
}
