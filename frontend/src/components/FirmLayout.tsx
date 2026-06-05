import { NavLink, Outlet } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useAuth } from "../auth/AuthProvider";
import i18n from "../i18n";

const NAV = [
  { to: "/dashboard", key: "nav.dashboard" },
  { to: "/companies", key: "nav.companies" },
  { to: "/statements", key: "nav.statements" },
  { to: "/taxes", key: "nav.taxes" },
  { to: "/payroll", key: "nav.payroll" },
  { to: "/reports", key: "nav.reports" },
  { to: "/notifications", key: "nav.notifications" },
  { to: "/tasks", key: "nav.tasks" },
];

/** Firm app shell (employee/admin): sidebar + top bar, per the prototype. */
export function FirmLayout() {
  const { t } = useTranslation();
  const { role, signOut } = useAuth();

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">MyFinance</div>
        <nav>
          {NAV.map((item) => (
            <NavLink key={item.to} to={item.to}>
              {t(item.key)}
            </NavLink>
          ))}
          {role === "SUPER_ADMIN" && <NavLink to="/admin/tenants">{t("nav.tenants")}</NavLink>}
          {role === "TENANT_ADMIN" && <NavLink to="/settings">{t("nav.settings")}</NavLink>}
        </nav>
      </aside>
      <header className="topbar">
        <button onClick={() => i18n.changeLanguage(i18n.language === "ro" ? "en" : "ro")}>
          {i18n.language === "ro" ? "EN" : "RO"}
        </button>
        <button onClick={() => void signOut()}>{t("auth.logout")}</button>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
