import type { ReportData, ReportItem, TrendPoint } from "../api/reports";

/** Shared SVG report charts — used by the staff charts modal and the representative portal. */

const money = (n: number) => (n ?? 0).toLocaleString("ro-RO", { minimumFractionDigits: 0, maximumFractionDigits: 0 });
const PALETTE = ["#14b8a6", "#0f766e", "#2d9cdb", "#f59e0b", "#8b5cf6", "#78807e"];

export function ChartCard({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ border: "1px solid var(--border)", borderRadius: 12, background: "var(--surface)", padding: 14 }}>
      <div style={{ fontSize: 10, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--text-muted)", fontWeight: 700, marginBottom: 10 }}>{title}</div>
      {children}
    </div>
  );
}

/** Horizontal Venituri / Cheltuieli / Profit bars. */
export function PlBars({ r }: { r: ReportData }) {
  const expenses = r.profitLoss.operatingExpenses + r.profitLoss.incomeTax;
  const max = Math.max(r.profitLoss.revenue, expenses, 1);
  const rows: [string, number, string][] = [
    ["Venituri", r.profitLoss.revenue, "#218c73"],
    ["Cheltuieli", expenses, "#dc4c4c"],
    ["Profit net", Math.max(0, r.profitLoss.netProfit), "#14b8a6"],
  ];
  return (
    <div style={{ display: "grid", gap: 10 }}>
      {rows.map(([label, v, c]) => (
        <div key={label} style={{ display: "grid", gridTemplateColumns: "84px 1fr", alignItems: "center", gap: 8 }}>
          <span style={{ fontSize: 12.5, color: "var(--text-secondary)" }}>{label}</span>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <div style={{ height: 18, width: `${Math.max(2, (v / max) * 100)}%`, background: c, borderRadius: 5 }} />
            <span className="mono" style={{ fontSize: 12, fontWeight: 600, whiteSpace: "nowrap" }}>{money(v)}</span>
          </div>
        </div>
      ))}
    </div>
  );
}

/** Expense-breakdown donut with legend (SVG stroke-dasharray slices). */
export function Donut({ items }: { items: ReportItem[] }) {
  const total = items.reduce((s, i) => s + i.amount, 0) || 1;
  const R = 54;
  const C = 2 * Math.PI * R;
  let offset = 0;
  return (
    <div style={{ display: "flex", gap: 18, alignItems: "center", flexWrap: "wrap" }}>
      <svg width="140" height="140" viewBox="0 0 140 140" style={{ flexShrink: 0 }}>
        <g transform="rotate(-90 70 70)">
          {items.map((it, i) => {
            const len = (it.amount / total) * C;
            const el = (
              <circle key={i} cx="70" cy="70" r={R} fill="none" stroke={PALETTE[i % PALETTE.length]}
                strokeWidth="22" strokeDasharray={`${len} ${C - len}`} strokeDashoffset={-offset} />
            );
            offset += len;
            return el;
          })}
        </g>
      </svg>
      <div style={{ display: "grid", gap: 4, minWidth: 0, flex: "1 1 180px" }}>
        {items.map((it, i) => (
          <div key={i} style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 12, minWidth: 0 }}>
            <span style={{ width: 10, height: 10, borderRadius: 2, background: PALETTE[i % PALETTE.length], flexShrink: 0 }} />
            <span style={{ flex: 1, minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", color: "var(--text-secondary)" }}>{it.label}</span>
            <span className="mono" style={{ fontWeight: 600, flexShrink: 0 }}>{Math.round((it.amount / total) * 100)}%</span>
          </div>
        ))}
      </div>
    </div>
  );
}

/**
 * Revenue & net-profit line chart across months. When the series ends with `projected` points (a
 * forecast), those are drawn as a dashed continuation with a shaded confidence band and a boundary
 * marker — an estimate, visually distinct from the actuals.
 */
export function Trend({ points, loading, emptyLabel, forecastLabel }:
  { points: TrendPoint[]; loading: boolean; emptyLabel: string; forecastLabel?: string }) {
  if (loading) return <span style={{ color: "var(--text-muted)", fontSize: 12.5 }}>…</span>;
  if (points.length < 2) return <div style={{ color: "var(--text-faint)", fontSize: 12.5 }}>{emptyLabel}</div>;
  const W = 560, H = 150, P = 28;
  const firstProj = points.findIndex((p) => p.projected);
  const hasForecast = firstProj > 0; // need at least one actual before the projection to anchor it
  const lastActual = hasForecast ? firstProj - 1 : points.length - 1;

  const vals = points.flatMap((p) => [
    p.revenue, p.netProfit,
    p.revenueHigh ?? p.revenue, p.netProfitHigh ?? p.netProfit,
    p.revenueLow ?? p.revenue, p.netProfitLow ?? p.netProfit,
  ]);
  const max = Math.max(...vals, 1);
  const min = Math.min(...vals, 0);
  const x = (i: number) => P + (i / (points.length - 1)) * (W - 2 * P);
  const y = (v: number) => H - P - ((v - min) / (max - min || 1)) * (H - 2 * P);

  const line = (key: "revenue" | "netProfit", from: number, to: number) =>
    points.slice(from, to + 1)
      .map((p, idx) => `${idx === 0 ? "M" : "L"}${x(from + idx).toFixed(1)},${y(p[key]).toFixed(1)}`).join(" ");

  // Area between the low/high band across the projected region, anchored at the last actual point.
  const band = (lowKey: "revenueLow" | "netProfitLow", highKey: "revenueHigh" | "netProfitHigh",
                actualKey: "revenue" | "netProfit"): string | null => {
    if (!hasForecast || !points.slice(firstProj).some((p) => p[highKey] != null && p[lowKey] != null)) return null;
    const idxs = Array.from({ length: points.length - lastActual }, (_, k) => lastActual + k);
    const pt = (i: number, key: "revenueLow" | "netProfitLow" | "revenueHigh" | "netProfitHigh") => {
      const p = points[i];
      const v = i === lastActual ? p[actualKey] : (p[key] ?? p[actualKey]);
      return `${x(i).toFixed(1)},${y(v).toFixed(1)}`;
    };
    return [...idxs.map((i) => pt(i, highKey)), ...idxs.map((i) => pt(i, lowKey)).reverse()].join(" ");
  };
  const revBand = band("revenueLow", "revenueHigh", "revenue");
  const npBand = band("netProfitLow", "netProfitHigh", "netProfit");

  return (
    <div>
      <svg width="100%" viewBox={`0 0 ${W} ${H}`} style={{ maxWidth: W }}>
        <line x1={P} y1={H - P} x2={W - P} y2={H - P} stroke="var(--border)" />
        {revBand && <polygon points={revBand} fill="#0f766e" opacity={0.1} />}
        {npBand && <polygon points={npBand} fill="#14b8a6" opacity={0.1} />}
        {/* actuals */}
        <path d={line("revenue", 0, lastActual)} fill="none" stroke="#0f766e" strokeWidth="2.5" />
        <path d={line("netProfit", 0, lastActual)} fill="none" stroke="#14b8a6" strokeWidth="2.5" strokeDasharray="4 3" />
        {/* forecast continuation */}
        {hasForecast && (
          <>
            <line x1={x(lastActual)} y1={P / 2} x2={x(lastActual)} y2={H - P} stroke="var(--border)" strokeDasharray="3 3" />
            <path d={line("revenue", lastActual, points.length - 1)} fill="none" stroke="#0f766e" strokeWidth="2" strokeDasharray="2 3" opacity={0.65} />
            <path d={line("netProfit", lastActual, points.length - 1)} fill="none" stroke="#14b8a6" strokeWidth="2" strokeDasharray="2 3" opacity={0.65} />
            {points.slice(firstProj).map((p, k) => (
              <circle key={`p${firstProj + k}`} cx={x(firstProj + k)} cy={y(p.revenue)} r="2.4" fill="#0f766e" opacity={0.7} />
            ))}
          </>
        )}
        {points.map((p, i) => (
          <text key={i} x={x(i)} y={H - 8} fontSize="9" textAnchor="middle" fill="var(--text-muted)">{p.periodMonth.slice(0, 7)}</text>
        ))}
      </svg>
      <div style={{ display: "flex", flexWrap: "wrap", gap: "4px 16px", fontSize: 11.5, marginTop: 4 }}>
        <Legend color="#0f766e" label="Venituri" />
        <Legend color="#14b8a6" label="Profit net" />
        {hasForecast && forecastLabel && (
          <span style={{ display: "inline-flex", alignItems: "center", gap: 5, color: "var(--text-muted)" }}>
            <span style={{ width: 12, height: 0, borderTop: "2px dashed var(--text-muted)", display: "inline-block" }} />{forecastLabel}
          </span>
        )}
      </div>
    </div>
  );
}

/** Balance-sheet composition: Active vs Datorii + Capitaluri (stacked). */
export function Position({ r }: { r: ReportData }) {
  const bs = r.balanceSheet;
  const total = Math.max(bs.totalAssets, bs.totalLiabilities + bs.totalEquity, 1);
  const seg = (v: number, c: string, label: string) => (
    <div style={{ width: `${(v / total) * 100}%`, background: c, minWidth: v > 0 ? 2 : 0 }} title={`${label}: ${money(v)}`} />
  );
  return (
    <div style={{ display: "grid", gap: 12 }}>
      <Stacked label="Active" amount={bs.totalAssets}>{seg(bs.totalAssets, "#14b8a6", "Active")}</Stacked>
      <Stacked label="Pasive" amount={bs.totalLiabilities + bs.totalEquity}>
        {seg(bs.totalLiabilities, "#dc4c4c", "Datorii")}
        {seg(bs.totalEquity, "#2d9cdb", "Capitaluri")}
      </Stacked>
      <div style={{ display: "flex", flexWrap: "wrap", gap: "4px 16px", fontSize: 11.5 }}>
        <Legend color="#14b8a6" label="Active" />
        <Legend color="#dc4c4c" label="Datorii" />
        <Legend color="#2d9cdb" label="Capitaluri proprii" />
      </div>
    </div>
  );
}

function Stacked({ label, amount, children }: { label: string; amount: number; children: React.ReactNode }) {
  return (
    <div style={{ display: "grid", gridTemplateColumns: "84px 1fr 110px", alignItems: "center", gap: 8 }}>
      <span style={{ fontSize: 12.5, color: "var(--text-secondary)" }}>{label}</span>
      <div style={{ display: "flex", height: 20, borderRadius: 5, overflow: "hidden", background: "var(--th-bg)" }}>{children}</div>
      <span className="mono" style={{ fontSize: 12, fontWeight: 600, textAlign: "right" }}>{money(amount)}</span>
    </div>
  );
}

export function Kpis({ r, t }: { r: ReportData; t: (k: string) => string }) {
  const k = r.kpis;
  const tiles: [string, string][] = [
    [t("reports.kpi.grossMargin"), k.grossMargin == null ? "—" : `${k.grossMargin}%`],
    [t("reports.kpi.netMargin"), k.netMargin == null ? "—" : `${k.netMargin}%`],
    [t("reports.kpi.currentRatio"), k.currentRatio == null ? "—" : String(k.currentRatio)],
    [t("reports.kpi.debtEquity"), k.debtToEquity == null ? "—" : String(k.debtToEquity)],
  ];
  return (
    <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(120px, 1fr))", gap: 10 }}>
      {tiles.map(([label, val]) => (
        <div key={label} style={{ border: "1px solid var(--hair)", borderRadius: 10, padding: "10px 12px", background: "var(--bg)" }}>
          <div className="mono" style={{ fontSize: 19, fontWeight: 700, color: "var(--primary-dark)" }}>{val}</div>
          <div style={{ fontSize: 11, color: "var(--text-muted)", marginTop: 2 }}>{label}</div>
        </div>
      ))}
    </div>
  );
}

function Legend({ color, label }: { color: string; label: string }) {
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 5, color: "var(--text-secondary)" }}>
      <span style={{ width: 12, height: 3, background: color, borderRadius: 2, display: "inline-block" }} />{label}
    </span>
  );
}
