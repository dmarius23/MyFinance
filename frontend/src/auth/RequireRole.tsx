import { Navigate, Outlet } from "react-router-dom";
import { useAuth, type Role } from "./AuthProvider";

/**
 * Route guard. Redirects unauthenticated users to /login and authenticated-but-unauthorized users
 * to their landing page. NOTE: this is UX-only — the server is the source of truth for access
 * (RLS + role checks). Never trust the client.
 */
export function RequireRole({ allow }: { allow: Role[] }) {
  const { session, loading, role } = useAuth();

  if (loading) {
    return <div className="centered">Loading…</div>;
  }
  if (!session) {
    return <Navigate to="/login" replace />;
  }
  if (role && !allow.includes(role)) {
    return <Navigate to="/" replace />;
  }
  return <Outlet />;
}
