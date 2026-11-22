import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { gmailApi } from "../api/gmail";
import { ApiError } from "../lib/apiClient";

export function GmailOAuthCallback() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const oauthError = params.get("error");
    if (oauthError) {
      window.localStorage.removeItem("myfinance.gmail.oauth.connection");
      setError(
        oauthError === "access_denied"
          ? t("gmail.callbackDenied")
          : t("gmail.callbackFailed"),
      );
      return;
    }

    const code = params.get("code");
    const state = params.get("state");
    const connectionId = window.localStorage.getItem("myfinance.gmail.oauth.connection");
    if (!code || !state || !connectionId) {
      setError(t("gmail.callbackIncomplete"));
      return;
    }

    // completeOAuth also date-backfills mail that arrived while the previous token was dead.
    void gmailApi
      .completeOAuth(connectionId, { authorizationCode: code, state })
      .then((result) => {
        window.localStorage.removeItem("myfinance.gmail.oauth.connection");
        navigate("/data-sources", { replace: true, state: { gmailConnected: result } });
      })
      .catch((e: unknown) => {
        setError(e instanceof ApiError ? e.message : t("gmail.callbackFailed"));
      });
  }, [navigate, t]);

  return (
    <div className="centered" style={{ display: "grid", gap: 12, maxWidth: 420, textAlign: "center" }}>
      {error ? (
        <>
          <div style={{ color: "#b91c1c" }}>{error}</div>
          <button type="button" className="primary" onClick={() => navigate("/data-sources", { replace: true })}>
            {t("gmail.backToSettings")}
          </button>
        </>
      ) : (
        t("gmail.callbackProgress")
      )}
    </div>
  );
}
