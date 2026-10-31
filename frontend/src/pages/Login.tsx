import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { supabase } from "../lib/supabase";

/** Email+password sign-in (Google OAuth + staff MFA challenge are wired alongside Supabase Auth). */
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
   * Self-service password reset. The link Supabase mails is a recovery link, so following it raises
   * PASSWORD_RECOVERY and lands the user on the set-password screen. Always report success, even for an
   * unknown address, so this can't be used to enumerate accounts.
   */
  async function onForgotPassword() {
    setError(null);
    setNotice(null);
    if (!email.trim()) {
      setError(t("auth.resetNeedsEmail"));
      return;
    }
    setBusy(true);
    await supabase.auth.resetPasswordForEmail(email.trim(), { redirectTo: window.location.origin });
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
          style={{ width: "100%", marginTop: 8 }}
          onClick={() => void supabase.auth.signInWithOAuth({ provider: "google" })}
        >
          Google
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
