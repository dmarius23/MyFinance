import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { dashboardApi, type DashboardStatusFilter, type SectionStatus, type SectionTile } from "../api/dashboard";
import { usePeriod } from "../lib/period";
import { Icon } from "../components/Icon";

const SECTIONS = [
  { key: "statements", route: "/statements", icon: "statements" },
  { key: "taxes", route: "/taxes", icon: "taxes" },
  { key: "payroll", route: "/payroll", icon: "payroll" },
  { key: "reports", route: "/reports", icon: "reports" },
] as const;

/** MOD-11 Dashboard — monthly companies overview: section tiles + per-company status table. */
export function Dashboard() {
  const { t } = useTranslation();
  const { period } = usePeriod();
  const nav = useNavigate();
  const [status, setStatus] = useState<DashboardStatusFilter>("ALL");
  const [responsible, setResponsible] = useState<string>("");

  const q = useQuery({
    queryKey: ["dashboard", period, status, responsible],
    queryFn: () => dashboardApi.get(period, { status, responsible: responsible || undefined }),
  });
  const data = q.data;

  // Responsible options derived from the rows (stable when no responsible filter applied).
  const responsibles = useMemo(() => {
    const m = new Map<string, string>();
    (data?.rows ?? []).forEach((r) => { if (r.responsibleUserId && r.responsibleName) m.set(r.responsibleUserId, r.responsibleName); });
    return [...m.entries()];
  }, [data]);

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end" }}>
        <div>
          <div style={{ color: "var(--text-secondary)", fontSize: 12.5 }}>{t("dashboard.crumb")}</div>
          <h2 style={{ margin: "2px 0 0", fontSize: 21, letterSpacing: "-0.01em" }}>{t("dashboard.title")}</h2>
        </div>
      </div>

      {/* Section tiles */}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))", gap: 12 }}>
        {SECTIONS.map((s) => (
          <Tile key={s.key} label={t(`nav.${s.key}`)} icon={s.icon} tile={data?.tiles[s.key]} onClick={() => nav(s.route)} t={t} />
        ))}
        <div className="card" style={{ padding: 14, cursor: "pointer" }} onClick={() => nav("/companies")}>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <div style={tileIcon}><Icon name="companies" size={14} style={{ color: "var(--teal-chip-fg)" }} /></div>
            <span style={{ fontSize: 12.5, fontWeight: 600 }}>{t("dashboard.newCompanies")}</span>
          </div>
          <div className="mono" style={{ fontSize: 30, fontWeight: 700, marginTop: 8 }}>{data?.tiles.newCompanies ?? "—"}</div>
          <div style={{ fontSize: 11, color: "var(--text-muted)" }}>{t("dashboard.ofTotal", { n: data?.tiles.totalCompanies ?? 0 })}</div>
        </div>
      </div>

      {/* Filters */}
      <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
        <div style={{ display: "flex", gap: 2, background: "var(--th-bg)", borderRadius: 8, padding: 2 }}>
          {(["ALL", "ATTENTION", "COMPLETE"] as DashboardStatusFilter[]).map((s) => (
            <button key={s} onClick={() => setStatus(s)}
              style={{ border: "none", background: status === s ? "var(--surface)" : "transparent", borderRadius: 6, padding: "5px 10px", fontSize: 12.5, fontWeight: status === s ? 600 : 400, cursor: "pointer", boxShadow: status === s ? "0 1px 2px rgba(0,0,0,0.08)" : "none" }}>
              {t(`dashboard.filter.${s.toLowerCase()}`)}
            </button>
          ))}
        </div>
        <select value={responsible} onChange={(e) => setResponsible(e.target.value)}
          style={{ padding: "6px 9px", border: "1px solid var(--border)", borderRadius: 8, fontSize: 12.5, background: "var(--surface)" }}>
          <option value="">{t("dashboard.allResponsible")}</option>
          {responsibles.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
        </select>
      </div>

      {/* Company status table */}
      <div className="card" style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ minWidth: 880 }}>
          <div style={{ ...gridRow, background: "var(--th-bg)", ...thText }}>
            <div>{t("documents.company")}</div>
            <div>{t("dashboard.responsible")}</div>
            <div style={{ textAlign: "center" }}>{t("nav.statements")}</div>
            <div style={{ textAlign: "center" }}>{t("nav.taxes")}</div>
            <div style={{ textAlign: "center" }}>{t("nav.payroll")}</div>
            <div style={{ textAlign: "center" }}>{t("nav.reports")}</div>
            <div style={{ textAlign: "center" }}>{t("dashboard.requests")}</div>
            <div style={{ textAlign: "center" }}>{t("dashboard.overdue")}</div>
          </div>
          {q.isLoading && <div style={{ padding: 14, color: "var(--text-muted)" }}>{t("common.loading")}</div>}
          {data?.rows.map((r) => (
            <div key={r.companyId} style={{ ...gridRow, borderTop: "1px solid var(--hair)", cursor: "pointer" }}
              onClick={() => nav(`/companies/${r.companyId}`)}>
              <div>
                <div style={{ fontWeight: 600 }}>{r.legalName}</div>
                <div className="mono" style={{ color: "var(--text-muted)", fontSize: 11 }}>{r.cui}</div>
              </div>
              <div style={{ fontSize: 12, color: r.responsibleName ? "var(--text-secondary)" : "var(--text-faint)" }}>{r.responsibleName ?? "—"}</div>
              <Cell s={r.statements} t={t} />
              <Cell s={r.taxes} t={t} />
              <Cell s={r.payroll} t={t} />
              <Cell s={r.reports} t={t} />
              <div style={{ textAlign: "center" }}>{r.openRequests > 0 ? <span className="pill round warn">{r.openRequests}</span> : <span style={{ color: "var(--text-faint)" }}>—</span>}</div>
              <div style={{ textAlign: "center" }}>{r.overdue > 0 ? <span className="pill round danger">{t("dashboard.overdueChip")}</span> : <span style={{ color: "var(--text-faint)" }}>—</span>}</div>
            </div>
          ))}
          {data && data.rows.length === 0 && <div style={{ padding: 14, color: "var(--text-muted)" }}>{t("dashboard.noneMatch")}</div>}
        </div>
      </div>
    </div>
  );
}

function Tile({ label, icon, tile, onClick, t }:
  { label: string; icon: string; tile?: SectionTile; onClick: () => void; t: (k: string, o?: object) => string }) {
  const total = tile ? tile.done + tile.partial + tile.nothing : 0;
  const pct = (n: number) => total ? `${(n / total) * 100}%` : "0%";
  return (
    <div className="card" style={{ padding: 14, cursor: "pointer" }} onClick={onClick}>
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <div style={tileIcon}><Icon name={icon} size={14} style={{ color: "var(--teal-chip-fg)" }} /></div>
        <span style={{ fontSize: 12.5, fontWeight: 600 }}>{label}</span>
      </div>
      <div style={{ display: "flex", gap: 10, marginTop: 10, marginBottom: 8 }}>
        <Stat n={tile?.done} color="var(--dot-green)" label={t("dashboard.done")} />
        <Stat n={tile?.partial} color="var(--dot-orange)" label={t("dashboard.partial")} />
        <Stat n={tile?.nothing} color="var(--dot-red)" label={t("dashboard.nothing")} />
      </div>
      <div style={{ display: "flex", height: 7, borderRadius: 4, overflow: "hidden", background: "var(--hair)" }}>
        <div style={{ width: pct(tile?.done ?? 0), background: "var(--dot-green)" }} />
        <div style={{ width: pct(tile?.partial ?? 0), background: "var(--dot-orange)" }} />
        <div style={{ width: pct(tile?.nothing ?? 0), background: "var(--dot-red)" }} />
      </div>
    </div>
  );
}

function Stat({ n, color, label }: { n?: number; color: string; label: string }) {
  return (
    <div style={{ display: "flex", flexDirection: "column" }}>
      <span className="mono" style={{ fontSize: 18, fontWeight: 700 }}>{n ?? "—"}</span>
      <span style={{ fontSize: 10, color: "var(--text-muted)", display: "flex", alignItems: "center", gap: 3 }}>
        <span style={{ width: 7, height: 7, borderRadius: "50%", background: color }} />{label}
      </span>
    </div>
  );
}

function Cell({ s, t }: { s: SectionStatus; t: (k: string) => string }) {
  if (s === "NA") return <div style={{ textAlign: "center", color: "var(--text-faint)" }}>—</div>;
  const cls = s === "DONE" ? "ok" : s === "PARTIAL" ? "warn" : "danger";
  return <div style={{ textAlign: "center" }}><span className={`pill round ${cls}`} title={t(`dashboard.st.${s.toLowerCase()}`)}>{t(`dashboard.st.${s.toLowerCase()}`)}</span></div>;
}

const gridRow: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "minmax(180px,1.4fr) 120px 96px 96px 96px 96px 80px 84px",
  alignItems: "center", gap: 8, padding: "10px 16px",
};
const thText: React.CSSProperties = { fontSize: 9.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "#8a9794" };
const tileIcon: React.CSSProperties = { width: 26, height: 26, borderRadius: 7, background: "var(--teal-chip-bg)", display: "grid", placeItems: "center" };
