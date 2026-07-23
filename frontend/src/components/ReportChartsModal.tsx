import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { reportsApi } from "../api/reports";
import type { Granularity } from "../api/portal";
import { Icon } from "./Icon";
import { GranularitySelector } from "./GranularitySelector";
import { ChartCard, PlBars, Donut, Trend, Position, Kpis } from "./reportCharts";

/** Charts for a company's report (B skin): period selector, P&L bars, expense donut, trend, balance + KPIs. */
export function ReportChartsModal({ companyId, companyName, period, onClose }:
  { companyId: string; companyName: string; period: string; onClose: () => void }) {
  const { t } = useTranslation();
  const [gran, setGran] = useState<Granularity>("MONTH");
  const report = useQuery({ queryKey: ["report", companyId, period, gran], queryFn: () => reportsApi.report(companyId, period, gran) });
  // Trend is always a monthly series; request a 3-month forecast (a non-authoritative estimate).
  const trend = useQuery({ queryKey: ["report-trend", companyId, period], queryFn: () => reportsApi.trend(companyId, period, 12, 3) });
  const r = report.data?.report ?? null;
  const coverage = report.data;

  return (
    <div style={overlay} onClick={onClose}>
      <div style={modal} onClick={(e) => e.stopPropagation()}>
        <div style={header}>
          <div>
            <div style={{ color: "var(--chrome-muted)", fontSize: 11 }}>{t("reports.charts")}</div>
            <div style={{ color: "#f3f8f7", fontSize: 17, fontWeight: 700 }}>{companyName}</div>
            <div style={{ color: "var(--chrome-text)", fontSize: 12 }}>{r?.periodStart} — {r?.periodEnd}</div>
          </div>
          <button onClick={onClose} style={closeBtn}><Icon name="x" size={16} /></button>
        </div>

        <div style={{ padding: 18, overflowY: "auto", background: "var(--bg)", display: "grid", gap: 18 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
            <div style={{ flex: 1, minWidth: 200 }}><GranularitySelector value={gran} onChange={setGran} /></div>
            <button onClick={() => reportsApi.downloadPdf(companyId, period, gran)} disabled={!r} style={dlBtn(!!r)}>
              <Icon name="download" size={14} /> {t("reports.download")}
            </button>
          </div>
          {gran !== "MONTH" && r && coverage && !coverage.complete && (
            <div style={warn}>{t("portal.reportIncomplete", { present: coverage.monthsPresent, expected: coverage.monthsExpected })}</div>
          )}
          {report.isLoading && <p style={{ color: "var(--text-muted)" }}>{t("common.loading")}</p>}
          {report.isError && <p style={{ color: "var(--danger-fg)" }}>{t("reports.noReport")}</p>}
          {r && (
            <>
              {!r.balanced && <div style={warn}>{t("reports.unbalanced")}</div>}
              <ChartCard title={t("reports.chart.pl")}><PlBars r={r} /></ChartCard>
              <ChartCard title={t("reports.chart.expenses")}><Donut items={r.profitLoss.expenseItems} /></ChartCard>
              <ChartCard title={t("reports.chart.trend")}>
                <Trend points={trend.data ?? []} loading={trend.isLoading} emptyLabel={t("reports.trendEmpty")} forecastLabel={t("portal.forecast")} />
              </ChartCard>
              <ChartCard title={t("reports.chart.position")}><Position r={r} /></ChartCard>
              <ChartCard title={t("reports.chart.kpi")}><Kpis r={r} t={t} /></ChartCard>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

const overlay: React.CSSProperties = { position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex", alignItems: "flex-start", justifyContent: "center", padding: "4vh 16px", zIndex: 60 };
const modal: React.CSSProperties = { background: "var(--surface)", borderRadius: 14, width: "min(720px, 96vw)", maxHeight: "90vh", display: "flex", flexDirection: "column", overflow: "hidden", boxShadow: "var(--shadow-modal)" };
const header: React.CSSProperties = { display: "flex", justifyContent: "space-between", alignItems: "flex-start", background: "var(--chrome-bg)", padding: "12px 16px" };
const closeBtn: React.CSSProperties = { background: "none", border: "none", color: "var(--chrome-text)", cursor: "pointer" };
const warn: React.CSSProperties = { background: "var(--warn-bg, #fef3c7)", border: "1px solid var(--warn-bd, #fcd34d)", color: "var(--warn-fg, #92400e)", borderRadius: 10, padding: "8px 12px", fontSize: 12.5 };
const dlBtn = (on: boolean): React.CSSProperties => ({ display: "inline-flex", alignItems: "center", gap: 6, border: "1px solid var(--border)", borderRadius: 9, padding: "7px 12px", background: "var(--surface)", color: "var(--text)", fontSize: 12.5, fontWeight: 600, cursor: on ? "pointer" : "default", opacity: on ? 1 : 0.45 });
