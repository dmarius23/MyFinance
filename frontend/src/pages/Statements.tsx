import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { documentsSummaryApi } from "../api/documents";
import { reconciliationApi } from "../api/bank";
import { MonthBar } from "../components/MonthBar";
import { FilesModal } from "../components/FilesModal";
import { ReconModal } from "../components/ReconModal";

function pill(text: string, kind: "ok" | "bad" | "na") {
  const colors: Record<string, React.CSSProperties> = {
    ok: { background: "#dcfce7", color: "#166534" },
    bad: { background: "#fee2e2", color: "#991b1b" },
    na: { background: "var(--border)", color: "var(--text-muted)" },
  };
  return <span style={{ ...colors[kind], borderRadius: 999, padding: "2px 10px", fontSize: 12 }}>{text}</span>;
}

/** Statements & invoices — monthly company list (follows the prototype). */
export function Statements() {
  const { t } = useTranslation();
  const [period, setPeriod] = useState(() => new Date().toISOString().slice(0, 7) + "-01");
  const [filesFor, setFilesFor] = useState<{ id: string; name: string } | null>(null);
  const [reconFor, setReconFor] = useState<{ id: string; name: string } | null>(null);

  const companies = useQuery({ queryKey: ["companies"], queryFn: companiesApi.list });
  const summary = useQuery({
    queryKey: ["doc-summary", period],
    queryFn: () => documentsSummaryApi.summary(period),
  });
  const completeness = useQuery({
    queryKey: ["recon-summary", period],
    queryFn: () => reconciliationApi.summary(period),
  });
  const completenessBy = new Map((completeness.data ?? []).map((c) => [c.companyId, c.completeness]));

  const byCompany = new Map((summary.data ?? []).map((s) => [s.companyId, s]));

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="card">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <div>
            <div style={{ color: "var(--text-muted)", fontSize: 12.5 }}>{t("statements.crumb")}</div>
            <h1 style={{ margin: "2px 0 0" }}>{t("documents.title")}</h1>
          </div>
          <MonthBar value={period} onChange={setPeriod} />
        </div>
      </div>

      <div className="card">
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8 }}>{t("documents.company")}</th>
              <th style={{ padding: 8 }}>{t("statements.bankStatement")}</th>
              <th style={{ padding: 8 }}>{t("statements.invoices")}</th>
              <th style={{ padding: 8 }}>{t("statements.completeness")}</th>
              <th style={{ padding: 8 }}>{t("statements.reminder")}</th>
              <th style={{ padding: 8 }}>{t("statements.files")}</th>
              <th style={{ padding: 8 }} />
            </tr>
          </thead>
          <tbody>
            {(companies.data ?? []).map((c) => {
              const s = byCompany.get(c.id);
              const hasBank = s?.hasBankStatement ?? false;
              return (
                <tr key={c.id} style={{ borderTop: "1px solid var(--border)" }}>
                  <td style={{ padding: 8 }}>
                    <b>{c.legalName}</b>
                    <div style={{ color: "var(--text-muted)", fontSize: 12 }}>{c.cui}{c.locality ? ` · ${c.locality}` : ""}</div>
                  </td>
                  <td style={{ padding: 8 }}>{hasBank ? pill(t("statements.uploaded"), "ok") : pill(t("statements.missing"), "bad")}</td>
                  <td style={{ padding: 8 }}>{s?.hasInvoiceOrReceipt ? pill(t("statements.uploaded"), "ok") : pill(t("statements.missing"), "bad")}</td>
                  <td style={{ padding: 8 }}>{(() => {
                    const cs = completenessBy.get(c.id) ?? "NOT_STARTED";
                    const kind = cs === "COMPLETE" ? "ok" : cs === "PARTIAL" ? "bad" : "na";
                    return pill(t(`completeness.${cs}`), kind as "ok" | "bad" | "na");
                  })()}</td>
                  <td style={{ padding: 8 }}>{pill("—", "na")}</td>
                  <td style={{ padding: 8 }}>
                    <button onClick={() => setFilesFor({ id: c.id, name: c.legalName })}>
                      {s?.fileCount ?? 0} {t("statements.files").toLowerCase()}
                    </button>
                  </td>
                  <td style={{ padding: 8, textAlign: "right", whiteSpace: "nowrap" }}>
                    <button
                      disabled={!hasBank}
                      title={hasBank ? "" : t("recon.noStatement")}
                      onClick={() => setReconFor({ id: c.id, name: c.legalName })}
                    >
                      {t("statements.transactions")}
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {filesFor && (
        <FilesModal companyId={filesFor.id} companyName={filesFor.name} period={period} onClose={() => setFilesFor(null)} />
      )}
      {reconFor && (
        <ReconModal companyId={reconFor.id} companyName={reconFor.name} period={period} onClose={() => setReconFor(null)} />
      )}
    </div>
  );
}
