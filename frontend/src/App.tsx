import { Navigate, Route, Routes } from "react-router-dom";
import { FirmLayout } from "./components/FirmLayout";
import { PagePlaceholder } from "./components/PagePlaceholder";
import { RequireRole } from "./auth/RequireRole";
import { useAuth, type Role } from "./auth/AuthProvider";
import { Login } from "./pages/Login";
import { Companies } from "./pages/Companies";
import { Dashboard } from "./pages/Dashboard";
import { CompanyDetail } from "./pages/CompanyDetail";
import { RepHome } from "./pages/RepHome";
import { Settings } from "./pages/Settings";
import { Statements } from "./pages/Statements";
import { TaxPayments } from "./pages/TaxPayments";
import { Payroll } from "./pages/Payroll";
import { Reports } from "./pages/Reports";
import { Notifications } from "./pages/Notifications";
import { Tasks } from "./pages/Tasks";
import { Team } from "./pages/Team";

/** Where a signed-in user belongs based on their role (reps → portal, staff → dashboard). */
export function homeFor(role: Role | null): string {
  return role === "REPRESENTATIVE" ? "/portal" : "/dashboard";
}

/** Role-aware landing for "/" and unknown paths — avoids bouncing reps into the staff-only dashboard. */
function RoleHome() {
  const { loading, session, role } = useAuth();
  if (loading) return <div className="centered">Loading…</div>;
  if (!session) return <Navigate to="/login" replace />;
  return <Navigate to={homeFor(role)} replace />;
}

/**
 * Routing — one route per page (no stacked sections). Guards are UX-only; the server enforces
 * access via RLS + role checks.
 */
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />

      {/* Representative PWA — scoped to their single company */}
      <Route element={<RequireRole allow={["REPRESENTATIVE"]} />}>
        <Route path="/portal" element={<RepHome />} />
      </Route>

      {/* Firm app — staff */}
      <Route element={<RequireRole allow={["TENANT_ADMIN", "EMPLOYEE", "SUPER_ADMIN"]} />}>
        <Route element={<FirmLayout />}>
          <Route path="/dashboard" element={<Dashboard />} />
          <Route path="/companies" element={<Companies />} />
          <Route path="/companies/:id" element={<CompanyDetail />} />
          <Route path="/statements" element={<Statements />} />
          <Route path="/taxes" element={<TaxPayments />} />
          <Route path="/payroll" element={<Payroll />} />
          <Route path="/reports" element={<Reports />} />
          <Route path="/notifications" element={<Notifications />} />
          <Route path="/tasks" element={<Tasks />} />
        </Route>
      </Route>

      {/* Settings + Team — TENANT_ADMIN only */}
      <Route element={<RequireRole allow={["TENANT_ADMIN"]} />}>
        <Route element={<FirmLayout />}>
          <Route path="/settings" element={<Settings />} />
          <Route path="/team" element={<Team />} />
        </Route>
      </Route>

      {/* Super-admin */}
      <Route element={<RequireRole allow={["SUPER_ADMIN"]} />}>
        <Route element={<FirmLayout />}>
          <Route path="/admin/tenants" element={<PagePlaceholder title="Tenant admin" module="MOD-01" />} />
        </Route>
      </Route>

      <Route path="/" element={<RoleHome />} />
      <Route path="*" element={<RoleHome />} />
    </Routes>
  );
}
