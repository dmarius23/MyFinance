import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { supabase } from "../lib/supabase";
import { useAuth } from "../auth/AuthProvider";

/**
 * Shown after a user follows an invitation / password-reset link. Supabase has established a temporary
 * recovery session (detected in the URL); the user must choose a password before entering the app —
 * they are NOT dropped straight into the dashboard. On success, the recovery flag clears and routing
 * sends them to their role's home.
 */
export function SetPassword() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { clearRecovery } = useAuth();
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (password.length < 8) {
      setError(t("auth.pwTooShort"));
      return;
    }
    if (password !== confirm) {
      setError(t("auth.pwMismatch"));
      return;
    }
    setBusy(true);
    const { error: updateError } = await supabase.auth.updateUser({ password });
    setBusy(false);
    if (updateError) {
      setError(updateError.message);
      return;
    }
    clearRecovery();
    navigate("/", { replace: true });
  }

  return (
    <div className="centered">
      <form className="card" style={{ width: 360 }} onSubmit={onSubmit}>
        <h1 style={{ marginTop: 0, color: "var(--primary)" }}>MyFinance</h1>
        <h2 style={{ marginTop: 0, fontSize: 18 }}>{t("auth.setPasswordTitle")}</h2>
        <p style={{ color: "var(--text-muted)", fontSize: 13, marginTop: 0 }}>{t("auth.setPasswordIntro")}</p>
        <label>
          {t("auth.newPassword")}
          <input type="password" value={password} autoComplete="new-password" required
            onChange={(e) => setPassword(e.target.value)}
            style={{ width: "100%", padding: 8, margin: "6px 0 12px" }} />
        </label>
        <label>
          {t("auth.confirmPassword")}
          <input type="password" value={confirm} autoComplete="new-password" required
            onChange={(e) => setConfirm(e.target.value)}
            style={{ width: "100%", padding: 8, margin: "6px 0 12px" }} />
        </label>
        {error && <p style={{ color: "#dc2626" }}>{error}</p>}
        <button className="primary" type="submit" disabled={busy} style={{ width: "100%" }}>
          {busy ? t("common.loading") : t("auth.setPasswordCta")}
        </button>
      </form>
    </div>
  );
}
