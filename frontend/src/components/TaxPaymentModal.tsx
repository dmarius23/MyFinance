import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { taxPaymentsApi } from "../api/taxes";
import { documentsApi } from "../api/documents";
import { ApiError } from "../lib/apiClient";

function money(n: number) {
  return n.toLocaleString("ro-RO");
}

/** MOD-07 — per-company tax payment breakdown: declarations, grouped payment lines, email preview. */
export function TaxPaymentModal({ companyId, companyName, period, onClose }:
  { companyId: string; companyName: string; period: string; onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const fileRef = useRef<HTMLInputElement>(null);
  const [copied, setCopied] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ["tax-payments", companyId, period],
    queryFn: () => taxPaymentsApi.summary(companyId, period),
  });

  const upload = useMutation({
    mutationFn: (file: File) => documentsApi.upload(companyId, period, file),
    onSuccess: () => {
      setUploadError(null);
      void qc.invalidateQueries({ queryKey: ["tax-payments", companyId, period] });
      void qc.invalidateQueries({ queryKey: ["doc-summary", period] });
    },
    onError: (e) => setUploadError(e instanceof ApiError ? e.message : "Upload failed"),
  });

  const onPick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) upload.mutate(file);
    e.target.value = "";
  };

  const copyEmail = async () => {
    if (!data?.emailBody) return;
    await navigator.clipboard.writeText(data.emailBody);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  return (
    <div style={overlay} onClick={onClose}>
      <div style={modal} onClick={(e) => e.stopPropagation()}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
          <h2 style={{ margin: 0 }}>{t("taxes.title")} — {companyName}</h2>
          <button onClick={onClose} style={{ border: "none", background: "none", fontSize: 18, cursor: "pointer" }}>✕</button>
        </div>

        {isLoading && <p>{t("common.loading")}</p>}
        {error && <p style={{ color: "#dc2626" }}>{error instanceof ApiError ? error.message : "Failed to load"}</p>}

        {data && (
          <>
            {/* Declarations */}
            <h3 style={h3}>{t("taxes.declarations")}</h3>
            {data.declarations.length === 0 ? (
              <p style={{ color: "var(--text-muted)" }}>{t("taxes.noDeclarations")}</p>
            ) : (
              <table style={table}>
                <thead><tr style={th}>
                  <td style={td}>{t("taxes.form")}</td><td style={td}>{t("taxes.file")}</td>
                  <td style={{ ...td, textAlign: "right" }}>{t("taxes.amount")}</td>
                </tr></thead>
                <tbody>
                  {data.declarations.map((d) => (
                    <tr key={d.documentId} style={{ borderTop: "1px solid var(--border)" }}>
                      <td style={td}><b>{d.type}</b></td>
                      <td style={{ ...td, color: "var(--text-muted)", fontSize: 12 }}>{d.filename}</td>
                      <td style={{ ...td, textAlign: "right" }}>
                        {money(d.computedTotal)}
                        {d.mismatch && (
                          <span title={t("taxes.mismatchTip", { declared: d.declaredTotal })}
                            style={{ marginLeft: 6, color: "#b45309" }}>
                            ⚠ {t("taxes.mismatch")}
                          </span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            {/* Payment lines */}
            <h3 style={h3}>{t("taxes.toPay")}</h3>
            {data.paymentLines.length === 0 ? (
              <p style={{ color: "var(--text-muted)" }}>{t("taxes.noLines")}</p>
            ) : (
              <table style={table}>
                <thead><tr style={th}>
                  <td style={td}>{t("taxes.explanation")}</td><td style={td}>IBAN</td>
                  <td style={td}>{t("taxes.due")}</td>
                  <td style={{ ...td, textAlign: "right" }}>{t("taxes.amount")}</td>
                </tr></thead>
                <tbody>
                  {data.paymentLines.map((l, i) => (
                    <tr key={i} style={{ borderTop: "1px solid var(--border)" }}>
                      <td style={td}>{l.explanation}</td>
                      <td style={{ ...td, fontFamily: "monospace", fontSize: 12 }}>{l.iban}</td>
                      <td style={td}>{l.scadenta ?? "—"}</td>
                      <td style={{ ...td, textAlign: "right" }}><b>{money(l.amount)}</b></td>
                    </tr>
                  ))}
                  <tr style={{ borderTop: "2px solid var(--border)" }}>
                    <td style={td} colSpan={3}><b>{t("taxes.total")}</b></td>
                    <td style={{ ...td, textAlign: "right" }}><b>{money(data.totalToPay)}</b></td>
                  </tr>
                </tbody>
              </table>
            )}

            {/* Unconfigured IBANs warning */}
            {data.unconfigured.length > 0 && (
              <div style={warn}>
                ⚠ {t("taxes.unconfigured")}:{" "}
                {data.unconfigured.map((u) => `${u.category} (${money(u.amount)})`).join(", ")}. {t("taxes.setInSettings")}
              </div>
            )}

            {/* Email preview */}
            {data.emailBody && (
              <>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginTop: 12 }}>
                  <h3 style={{ ...h3, marginBottom: 4 }}>{t("taxes.emailPreview")}</h3>
                  <button onClick={copyEmail}>{copied ? t("taxes.copied") : t("taxes.copy")}</button>
                </div>
                <textarea readOnly value={data.emailBody}
                  style={{ width: "100%", minHeight: 220, fontFamily: "inherit", fontSize: 13, boxSizing: "border-box" }} />
              </>
            )}

            {/* Upload declarations */}
            <div style={{ marginTop: 12, borderTop: "1px solid var(--border)", paddingTop: 10 }}>
              {uploadError && <p style={{ color: "#dc2626" }}>{uploadError}</p>}
              <input ref={fileRef} type="file" accept="application/pdf" onChange={onPick} style={{ display: "none" }} />
              <button onClick={() => fileRef.current?.click()} disabled={upload.isPending}>
                {upload.isPending ? t("taxes.uploading") : t("taxes.uploadDeclaration")}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

const overlay: React.CSSProperties = {
  position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex",
  alignItems: "flex-start", justifyContent: "center", padding: "5vh 16px", zIndex: 50, overflowY: "auto",
};
const modal: React.CSSProperties = {
  background: "var(--card-bg, #fff)", borderRadius: 12, padding: 20, width: "min(760px, 100%)",
};
const h3: React.CSSProperties = { margin: "16px 0 6px", fontSize: 14 };
const table: React.CSSProperties = { width: "100%", borderCollapse: "collapse" };
const th: React.CSSProperties = { textAlign: "left", color: "var(--text-muted)" };
const td: React.CSSProperties = { padding: 6 };
const warn: React.CSSProperties = {
  marginTop: 10, padding: "8px 12px", borderRadius: 8, background: "#fef3c7", color: "#92400e", fontSize: 13,
};
