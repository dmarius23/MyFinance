import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { gmailApi } from "../api/gmail";
import { ApiError } from "../lib/apiClient";

export function GmailOAuthCallback() {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const code = params.get("code");
    const state = params.get("state");
    const connectionId = window.localStorage.getItem("myfinance.gmail.oauth.connection");
    if (!code || !state || !connectionId) {
      setError("Răspunsul OAuth Gmail este incomplet.");
      return;
    }

    void gmailApi.completeOAuth(connectionId, { authorizationCode: code, state })
      .then(() => {
        window.localStorage.removeItem("myfinance.gmail.oauth.connection");
        navigate("/data-sources", { replace: true });
      })
      .catch((e: unknown) => {
        setError(e instanceof ApiError ? e.message : "Conectarea Gmail a eșuat.");
      });
  }, [navigate]);

  return (
    <div className="centered">
      {error ? <div style={{ color: "#b91c1c" }}>{error}</div> : "Se finalizează conectarea Gmail…"}
    </div>
  );
}
