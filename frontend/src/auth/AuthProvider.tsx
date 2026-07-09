import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import type { Session } from "@supabase/supabase-js";
import { supabase } from "../lib/supabase";
import { setActiveCompanyId } from "../lib/activeCompany";
import { unsubscribePushOnLogout } from "../lib/push";

export type Role = "SUPER_ADMIN" | "TENANT_ADMIN" | "EMPLOYEE" | "REPRESENTATIVE";

export interface AuthState {
  session: Session | null;
  loading: boolean;
  role: Role | null;
  tenantId: string | null;
  companyId: string | null;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthState | undefined>(undefined);

/**
 * Holds the Supabase session and derives the tenant identity claims that the backend also reads
 * from the JWT (tenant_id, role, company_id — injected by a Supabase access-token hook).
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const qc = useQueryClient();

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => {
      setSession(data.session);
      setLoading(false);
    });
    const { data: sub } = supabase.auth.onAuthStateChange((_event, next) => setSession(next));
    return () => sub.subscription.unsubscribe();
  }, []);

  const value = useMemo<AuthState>(() => {
    const claims = (session?.user?.app_metadata ?? {}) as Record<string, unknown>;
    const jwtClaims = session ? decodeClaims(session.access_token) : {};
    const role = (jwtClaims.role ?? claims.role ?? null) as Role | null;
    return {
      session,
      loading,
      role,
      tenantId: (jwtClaims.tenant_id ?? claims.tenant_id ?? null) as string | null,
      companyId: (jwtClaims.company_id ?? claims.company_id ?? null) as string | null,
      signOut: async () => {
        // Shared-device safety: unsubscribe this browser from push while the token is still valid.
        await unsubscribePushOnLogout();
        await supabase.auth.signOut();
        // Drop the cached company hint and every cached API response.
        setActiveCompanyId(null);
        qc.clear();
      },
    };
  }, [session, loading, qc]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within AuthProvider");
  }
  return ctx;
}

function decodeClaims(token: string): Record<string, unknown> {
  try {
    const payload = token.split(".")[1];
    return JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/")));
  } catch {
    return {};
  }
}
