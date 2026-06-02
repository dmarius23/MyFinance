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
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const { error: signInError } = await supabase.auth.signInWithPassword({ email, password });
    setBusy(false);
    if (signInError) {
      setError(signInError.message);
      return;
    }
    navigate("/dashboard", { replace: true });
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
      </form>
    </div>
  );
}
