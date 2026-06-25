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
      <svg width="140" height="140" viewBox="0 0 140 140">
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
      <div style={{ display: "grid", gap: 4, minWidth: 200, flex: 1 }}>
        {items.map((it, i) => (
          <div key={i} style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 12 }}>
            <span style={{ width: 10, height: 10, borderRadius: 2, background: PALETTE[i % PALETTE.length], flexShrink: 0 }} />
            <span style={{ flex: 1, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", color: "var(--text-secondary)" }}>{it.label}</span>
            <span className="mono" style={{ fontWeight: 600 }}>{Math.round((it.amount / total) * 100)}%</span>
          </div>
        ))}
      </div>
    </div>
  );
}

/** Revenue & net-profit line chart across months. */
export function Trend({ points, loading, emptyLabel }: { points: TrendPoint[]; loading: boolean; emptyLabel: string }) {
  if (loading) return <span style={{ color: "var(--text-muted)", fontSize: 12.5 }}>…</span>;
  if (points.length < 2) return <div style={{ color: "var(--text-faint)", fontSize: 12.5 }}>{emptyLabel}</div>;
  const W = 560, H = 150, P = 28;
  const max = Math.max(...points.map((p) => Math.max(p.revenue, p.netProfit)), 1);
  const min = Math.min(...points.map((p) => p.netProfit), 0);
  const x = (i: number) => P + (i / (points.length - 1)) * (W - 2 * P);
  const y = (v: number) => H - P - ((v - min) / (max - min || 1)) * (H - 2 * P);
  const path = (key: "revenue" | "netProfit") =>
    points.map((p, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${y(p[key]).toFixed(1)}`).join(" ");
  return (
    <div>
      <svg width="100%" viewBox={`0 0 ${W} ${H}`} style={{ maxWidth: W }}>
        <line x1={P} y1={H - P} x2={W - P} y2={H - P} stroke="var(--border)" />
        <path d={path("revenue")} fill="none" stroke="#0f766e" strokeWidth="2.5" />
        <path d={path("netProfit")} fill="none" stroke="#14b8a6" strokeWidth="2.5" strokeDasharray="4 3" />
        {points.map((p, i) => (
          <text key={i} x={x(i)} y={H - 8} fontSize="9" textAnchor="middle" fill="var(--text-muted)">{p.periodMonth.slice(0, 7)}</text>
        ))}
      </svg>
      <div style={{ display: "flex", gap: 16, fontSize: 11.5, marginTop: 4 }}>
        <Legend color="#0f766e" label="Venituri" />
        <Legend color="#14b8a6" label="Profit net" />
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
      <div style={{ display: "flex", gap: 16, fontSize: 11.5 }}>
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
