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
  /** True after following an invitation / reset link — the user must set a password before entering. */
  recovery: boolean;
  /** Clear the recovery flag once the password has been set. */
  clearRecovery: () => void;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthState | undefined>(undefined);

/**
 * Where the "must set a password" flag lives. It has to outlive a page reload: the invitation link
 * consumes its token on first load, so if the user refreshes the set-password screen the
 * PASSWORD_RECOVERY event never fires again — but the session persists, which would drop them into the
 * app with a password they never chose (and no way back in after signing out). sessionStorage, not
 * localStorage: the gate belongs to this tab's sign-in, and must not outlive the browser session.
 */
const RECOVERY_KEY = "mf.recovery";

function readRecoveryFlag(): boolean {
  try {
    return sessionStorage.getItem(RECOVERY_KEY) === "1";
  } catch {
    return false; // private mode / storage disabled — fall back to the in-memory flag only
  }
}

function writeRecoveryFlag(on: boolean): void {
  try {
    if (on) {
      sessionStorage.setItem(RECOVERY_KEY, "1");
    } else {
      sessionStorage.removeItem(RECOVERY_KEY);
    }
  } catch {
    // Non-fatal: the in-memory flag still gates this page load.
  }
}

/**
 * Holds the Supabase session and derives the tenant identity claims that the backend also reads
 * from the JWT (tenant_id, role, company_id — injected by a Supabase access-token hook).
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [recovery, setRecovery] = useState(readRecoveryFlag);
  const qc = useQueryClient();

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => {
      setSession(data.session);
      setLoading(false);
    });
    const { data: sub } = supabase.auth.onAuthStateChange((event, next) => {
      setSession(next);
      // Invitation / reset links arrive as a recovery session — force the set-password step instead of
      // dropping the user straight into the app. Persisted so a reload can't bypass it.
      if (event === "PASSWORD_RECOVERY") {
        writeRecoveryFlag(true);
        setRecovery(true);
      }
    });
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
      // Only gate while a session is actually live: updateUser({ password }) needs one, so a persisted
      // flag with no session would strand the user on a form that can never submit. Dropping the gate
      // sends them to /login instead, where "forgot password" gets them a fresh link.
      recovery: recovery && session !== null,
      clearRecovery: () => {
        writeRecoveryFlag(false);
        setRecovery(false);
      },
      signOut: async () => {
        // Shared-device safety: unsubscribe this browser from push while the token is still valid.
        await unsubscribePushOnLogout();
        await supabase.auth.signOut();
        // Drop the cached company hint and every cached API response.
        setActiveCompanyId(null);
        qc.clear();
        writeRecoveryFlag(false);
        setRecovery(false);
      },
    };
  }, [session, loading, recovery, qc]);

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
