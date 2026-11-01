import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { messagingApi } from "../api/messaging";
import { ApiError } from "../lib/apiClient";

/** Completes the server-bound Meta Embedded Signup callback without storing provider credentials in the browser. */
export function MetaWhatsAppCallback() {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const metaError = params.get("error_description") ?? params.get("error");
    const code = params.get("code");
    const state = params.get("state");
    const wabaId = params.get("waba_id") ?? params.get("wabaId");
    const phoneNumberId = params.get("phone_number_id") ?? params.get("phoneNumberId");
    const businessPortfolioId = params.get("business_id")
      ?? params.get("business_portfolio_id")
      ?? params.get("businessPortfolioId")
      ?? undefined;

    if (metaError) {
      setError(metaError);
      return;
    }
    if (!code || !state || !wabaId || !phoneNumberId) {
      setError("Meta returned an incomplete WhatsApp onboarding response.");
      return;
    }

    void messagingApi.completeMetaOnboarding({
      state,
      authorizationCode: code,
      wabaId,
      phoneNumberId,
      businessPortfolioId,
    }).then(() => {
      navigate("/data-sources?whatsapp=connected", { replace: true });
    }).catch((e: unknown) => {
      setError(e instanceof ApiError ? e.message : "WhatsApp connection failed.");
    });
  }, [navigate]);

  return (
    <div className="centered">
      {error
        ? <div style={{ color: "#b91c1c", maxWidth: 520, textAlign: "center" }}>{error}</div>
        : "Finishing WhatsApp connection…"}
    </div>
  );
}
