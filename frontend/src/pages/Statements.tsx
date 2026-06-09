import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { companiesApi } from "../api/companies";
import { documentsSummaryApi } from "../api/documents";
import { reconciliationApi } from "../api/bank";
import { MonthBar } from "../components/MonthBar";
import { FilesModal } from "../components/FilesModal";
import { ReconModal } from "../components/ReconModal";
import { SendReminderModal } from "../components/SendReminderModal";

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
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [sendList, setSendList] = useState<{ id: string; name: string }[] | null>(null);

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

  // A company needs a reminder when anything for the period is still missing or incomplete.
  const needsReminder = (id: string) => {
    const s = byCompany.get(id);
    const cs = completenessBy.get(id) ?? "NOT_STARTED";
    return !(s?.hasBankStatement ?? false) || !(s?.hasInvoiceOrReceipt ?? false) || cs !== "COMPLETE";
  };

  const rows = companies.data ?? [];
  const selectableIds = rows.filter((c) => needsReminder(c.id)).map((c) => c.id);
  const allSelected = selectableIds.length > 0 && selectableIds.every((id) => selected.has(id));

  // Reset the selection whenever the period changes — the missing set is period-specific.
  useEffect(() => { setSelected(new Set()); }, [period]);

  const toggle = (id: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  const toggleAll = () =>
    setSelected(allSelected ? new Set() : new Set(selectableIds));
  const nameOf = (id: string) => rows.find((c) => c.id === id)?.legalName ?? id;
  const sendSelected = () =>
    setSendList([...selected].map((id) => ({ id, name: nameOf(id) })));

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

      {/* Bulk bar — appears only when at least one company is selected. */}
      {selected.size > 0 && (
        <div className="card" style={{
          display: "flex", alignItems: "center", justifyContent: "space-between",
          padding: "10px 14px", borderColor: "var(--primary)",
        }}>
          <span style={{ fontSize: 13.5 }}>✓ <b>{selected.size}</b> {t("email.selected", { n: selected.size })}</span>
          <div style={{ display: "flex", gap: 8 }}>
            <button onClick={() => setSelected(new Set())}>{t("email.clear")}</button>
            <button className="primary" onClick={sendSelected}>✉ {t("email.sendN", { n: selected.size })}</button>
          </div>
        </div>
      )}

      <div className="card">
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ textAlign: "left", color: "var(--text-muted)" }}>
              <th style={{ padding: 8, width: 34 }}>
                <input type="checkbox" checked={allSelected} onChange={toggleAll}
                  disabled={selectableIds.length === 0} title={t("email.selectAll")}
                  style={{ cursor: selectableIds.length === 0 ? "default" : "pointer" }} />
              </th>
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
            {rows.map((c) => {
              const s = byCompany.get(c.id);
              const hasBank = s?.hasBankStatement ?? false;
              const selectable = needsReminder(c.id);
              return (
                <tr key={c.id} style={{
                  borderTop: "1px solid var(--border)",
                  background: selected.has(c.id) ? "var(--primary-light, #eef2ff)" : undefined,
                }}>
                  <td style={{ padding: 8, textAlign: "center" }}>
                    {selectable
                      ? <input type="checkbox" checked={selected.has(c.id)} onChange={() => toggle(c.id)}
                          style={{ cursor: "pointer" }} />
                      : <span style={{ color: "var(--text-muted)" }}>·</span>}
                  </td>
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
                  <td style={{ padding: 8 }}>
                    {selectable
                      ? <button onClick={() => setSendList([{ id: c.id, name: c.legalName }])}>
                          ✉ {t("email.send")}
                        </button>
                      : pill("—", "na")}
                  </td>
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
      {sendList && (
        <SendReminderModal companies={sendList} period={period} onClose={() => setSendList(null)} />
      )}
    </div>
  );
}
