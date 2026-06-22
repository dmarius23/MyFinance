import { Navigate, Route, Routes } from "react-router-dom";
import { FirmLayout } from "./components/FirmLayout";
import { PagePlaceholder } from "./components/PagePlaceholder";
import { RequireRole } from "./auth/RequireRole";
import { Login } from "./pages/Login";
import { Companies } from "./pages/Companies";
import { CompanyDetail } from "./pages/CompanyDetail";
import { RepHome } from "./pages/RepHome";
import { Settings } from "./pages/Settings";
import { Statements } from "./pages/Statements";
import { TaxPayments } from "./pages/TaxPayments";
import { Payroll } from "./pages/Payroll";
import { Reports } from "./pages/Reports";

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
          <Route path="/dashboard" element={<PagePlaceholder title="Dashboard" module="MOD-11" />} />
          <Route path="/companies" element={<Companies />} />
          <Route path="/companies/:id" element={<CompanyDetail />} />
          <Route path="/statements" element={<Statements />} />
          <Route path="/taxes" element={<TaxPayments />} />
          <Route path="/payroll" element={<Payroll />} />
          <Route path="/reports" element={<Reports />} />
          <Route path="/notifications" element={<PagePlaceholder title="Notifications" module="MOD-09" />} />
          <Route path="/tasks" element={<PagePlaceholder title="Tasks" module="MOD-10" />} />
        </Route>
      </Route>

      {/* Settings — TENANT_ADMIN only */}
      <Route element={<RequireRole allow={["TENANT_ADMIN"]} />}>
        <Route element={<FirmLayout />}>
          <Route path="/settings" element={<Settings />} />
        </Route>
      </Route>

      {/* Super-admin */}
      <Route element={<RequireRole allow={["SUPER_ADMIN"]} />}>
        <Route element={<FirmLayout />}>
          <Route path="/admin/tenants" element={<PagePlaceholder title="Tenant admin" module="MOD-01" />} />
        </Route>
      </Route>

      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}
