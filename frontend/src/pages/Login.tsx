import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { supabase } from "../lib/supabase";
import { authApi } from "../api/auth";

/**
 * Email+password sign-in, plus self-service password reset.
 *
 * No social sign-in: every OAuth provider is disabled on the Supabase project, so a "Sign in with
 * Google" button could only ever error ("provider is not enabled"). Re-adding one means enabling the
 * provider in the dashboard AND turning OFF "Allow new users to sign up" — otherwise any Google account
 * can mint an auth user with no app_metadata, i.e. no tenant_id/role, which lands in the app with no
 * access and no matching app_user row. (Invites use the admin API, so they are unaffected by that toggle.)
 */
export function Login() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setNotice(null);
    const { error: signInError } = await supabase.auth.signInWithPassword({ email, password });
    setBusy(false);
    if (signInError) {
      setError(signInError.message);
      return;
    }
    // Role-aware landing (reps → /portal, staff → /dashboard) is resolved at "/".
    navigate("/", { replace: true });
  }

  /**
   * Self-service password reset, sent by OUR backend so the message comes from the firm's own provider and
   * sender address rather than the identity provider's generic template. The link it mails is a recovery
   * link, so following it raises PASSWORD_RECOVERY and lands the user on the set-password screen.
   *
   * Always reports the same success notice — even for an unknown address, and even if the request itself
   * fails — so this cannot be used to enumerate accounts. (The server answers 204 regardless; the catch
   * covers transport errors, which would otherwise surface as a visible difference.)
   */
  async function onForgotPassword() {
    setError(null);
    setNotice(null);
    if (!email.trim()) {
      setError(t("auth.resetNeedsEmail"));
      return;
    }
    setBusy(true);
    try {
      await authApi.forgotPassword(email.trim());
    } catch {
      // Deliberately ignored: the notice below must not depend on the outcome.
    }
    setBusy(false);
    setNotice(t("auth.resetSent"));
  }

  return (
    <div className="centered">
      <form className="card" style={{ width: 360 }} onSubmit={onSubmit}>
        <h1 style={{ marginTop: 0, color: "var(--primary)" }}>MyFinance</h1>
        <label>
          Email
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            style={{ width: "100%", padding: 8, margin: "6px 0 12px" }}
          />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            style={{ width: "100%", padding: 8, margin: "6px 0 12px" }}
          />
        </label>
        {error && <p style={{ color: "#dc2626" }}>{error}</p>}
        {notice && <p style={{ color: "var(--text-secondary)", fontSize: 13 }}>{notice}</p>}
        <button className="primary" type="submit" disabled={busy} style={{ width: "100%" }}>
          {busy ? t("common.loading") : t("auth.login")}
        </button>
        <button
          type="button"
          disabled={busy}
          onClick={() => void onForgotPassword()}
          style={{
            width: "100%", marginTop: 8, background: "none", border: "none",
            color: "var(--primary)", textDecoration: "underline", cursor: "pointer", fontSize: 13,
          }}
        >
          {t("auth.forgotPassword")}
        </button>
      </form>
    </div>
  );
}
