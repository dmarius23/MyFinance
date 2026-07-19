import { Navigate, Route, Routes } from "react-router-dom";
import { FirmLayout } from "./components/FirmLayout";
import { RequireRole } from "./auth/RequireRole";
import { useAuth, type Role } from "./auth/AuthProvider";
import { Login } from "./pages/Login";
import { Companies } from "./pages/Companies";
import { Dashboard } from "./pages/Dashboard";
import { CompanyDetail } from "./pages/CompanyDetail";
import { RepHome } from "./pages/RepHome";
import { Settings } from "./pages/Settings";
import { Statements } from "./pages/Statements";
import { ReconcileWorkspace } from "./pages/ReconcileWorkspace";
import { TaxPayments } from "./pages/TaxPayments";
import { Payroll } from "./pages/Payroll";
import { Reports } from "./pages/Reports";
import { Notifications } from "./pages/Notifications";
import { Tasks } from "./pages/Tasks";
import { Team } from "./pages/Team";
import { DataSources } from "./pages/DataSources";
import { AdminReference } from "./pages/AdminReference";
import { AdminTenants } from "./pages/AdminTenants";

/** Where a signed-in user belongs based on their role (reps → portal, super admin → platform
 *  admin, tenant staff → dashboard). A super admin has no tenant, so it never lands on a
 *  tenant-scoped view. */
export function homeFor(role: Role | null): string {
  if (role === "REPRESENTATIVE") return "/portal";
  if (role === "SUPER_ADMIN") return "/admin/tenants";
  return "/dashboard";
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
      <Route element={<RequireRole allow={["TENANT_ADMIN", "EMPLOYEE"]} />}>
        <Route element={<FirmLayout />}>
          <Route path="/dashboard" element={<Dashboard />} />
          <Route path="/companies" element={<Companies />} />
          <Route path="/companies/:id" element={<CompanyDetail />} />
          <Route path="/statements" element={<Statements />} />
          <Route path="/statements/:companyId/reconcile" element={<ReconcileWorkspace />} />
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
          <Route path="/data-sources" element={<DataSources />} />
        </Route>
      </Route>

      {/* Super-admin */}
      <Route element={<RequireRole allow={["SUPER_ADMIN"]} />}>
        <Route element={<FirmLayout />}>
          <Route path="/admin/tenants" element={<AdminTenants />} />
          <Route path="/admin/reference" element={<AdminReference />} />
        </Route>
      </Route>

      <Route path="/" element={<RoleHome />} />
      <Route path="*" element={<RoleHome />} />
    </Routes>
  );
}
