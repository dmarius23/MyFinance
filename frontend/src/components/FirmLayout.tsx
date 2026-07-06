import { NavLink, Outlet, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useAuth } from "../auth/AuthProvider";
import i18n from "../i18n";
import { Icon } from "./Icon";
import { NotificationBell } from "./NotificationBell";
import { PeriodProvider, usePeriod } from "../lib/period";

const NAV = [
  { to: "/dashboard", key: "nav.dashboard", icon: "dashboard" },
  { to: "/statements", key: "nav.statements", icon: "statements" },
  { to: "/taxes", key: "nav.taxes", icon: "taxes" },
  { to: "/payroll", key: "nav.payroll", icon: "payroll" },
  { to: "/reports", key: "nav.reports", icon: "reports" },
  { to: "/tasks", key: "nav.tasks", icon: "tasks" },
] as const;

/** Routes where the topbar month stepper is meaningful. */
const MONTH_ROUTES = ["/dashboard", "/statements", "/taxes", "/payroll", "/reports"];

function monthLabel(period: string, lang: string) {
  return new Date(period).toLocaleDateString(lang === "ro" ? "ro-RO" : "en-US", { month: "long", year: "numeric" });
}

function Topbar() {
  const { t } = useTranslation();
  const { signOut } = useAuth();
  const { period, prev, next } = usePeriod();
  const loc = useLocation();
  const showStepper = MONTH_ROUTES.some((r) => loc.pathname.startsWith(r));
  const lang = i18n.language;

  return (
    <header className="topbar">
      <div className="crumb">MyFinance <b>/ {crumbFor(loc.pathname, t)}</b></div>
      <div className="spacer" />
      {showStepper && (
        <div className="stepper">
          <button onClick={prev} aria-label={t("common.prev")}><Icon name="chevronLeft" size={14} /></button>
          <span className="label">{monthLabel(period, lang)}</span>
          <button onClick={next} aria-label={t("common.next")}><Icon name="chevronRight" size={14} /></button>
        </div>
      )}
      <button className="lang" onClick={() => i18n.changeLanguage(lang === "ro" ? "en" : "ro")}>
        {lang === "ro" ? <span><b>RO</b> / EN</span> : <span>RO / <b>EN</b></span>}
      </button>
      <NotificationBell />
      <button className="logout" onClick={() => void signOut()}>{t("auth.logout")}</button>
    </header>
  );
}

function crumbFor(path: string, t: (k: string) => string): string {
  const map: Record<string, string> = {
    "/dashboard": "nav.dashboard", "/companies": "nav.companies", "/statements": "nav.statements",
    "/taxes": "nav.taxes", "/payroll": "nav.payroll", "/reports": "nav.reports", "/settings": "nav.settings",
    "/tasks": "nav.tasks", "/notifications": "notif.title", "/team": "nav.team",
    "/data-sources": "nav.dataSources",
  };
  const key = Object.keys(map).find((p) => path.startsWith(p));
  return key ? t(map[key]) : "";
}

/** Firm app shell (employee/admin): dark Console sidebar + topbar, light content. */
export function FirmLayout() {
  const { t } = useTranslation();
  const { role, signOut } = useAuth();

  return (
    <PeriodProvider>
      <div className="app-shell">
        <aside className="sidebar">
          <div className="brand">
            <div className="mark">M</div>
            <div>
              <div className="brand-name">MyFinance</div>
              <div className="brand-sub">ContaZone SRL</div>
            </div>
          </div>
          <nav>
            {NAV.map((item) => (
              <NavLink key={item.to} to={item.to}>
                <Icon name={item.icon} /> {t(item.key)}
              </NavLink>
            ))}
            <div className="nav-divider" />
            <NavLink to="/companies"><Icon name="companies" /> {t("nav.companies")}</NavLink>
            {role === "TENANT_ADMIN" && <NavLink to="/team"><Icon name="companies" /> {t("nav.team")}</NavLink>}
            {role === "TENANT_ADMIN" && <NavLink to="/data-sources"><Icon name="folder" /> {t("nav.dataSources")}</NavLink>}
            {role === "TENANT_ADMIN" && <NavLink to="/settings"><Icon name="settings" /> {t("nav.settings")}</NavLink>}
            {role === "SUPER_ADMIN" && <NavLink to="/admin/tenants"><Icon name="companies" /> {t("nav.tenants")}</NavLink>}
          </nav>
          <div className="spacer" />
          <div className="who">
            <div className="avatar">A</div>
            <div>
              <div className="who-name">{t("auth.account")}</div>
              <div className="who-role">{role ?? ""}</div>
            </div>
            <div style={{ flex: 1 }} />
            <button className="logout" onClick={() => void signOut()} title={t("auth.logout")}>
              <Icon name="x" size={14} style={{ color: "var(--chrome-muted)" }} />
            </button>
          </div>
        </aside>
        <Topbar />
        <main className="content">
          <Outlet />
        </main>
      </div>
    </PeriodProvider>
  );
}
