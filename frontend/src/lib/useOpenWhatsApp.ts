import { useCallback } from "react";
import { useTranslation } from "react-i18next";
import { whatsappApi } from "../api/whatsapp";
import { useToast } from "../components/Toast";
import { waMeUrl } from "./whatsapp";

/**
 * Returns a click handler for click-to-chat mode: resolves the company's representative phone + a
 * pre-filled message body, then opens the WhatsApp desktop app / Web with that chat and text ready to
 * send manually. The blank tab is opened synchronously (inside the user gesture) to dodge popup blockers,
 * then redirected once the phone + body resolve.
 */
export function useOpenWhatsApp(): (companyId: string, loadBody: () => Promise<string>) => void {
  const { t } = useTranslation();
  const { toast } = useToast();
  return useCallback((companyId: string, loadBody: () => Promise<string>) => {
    const win = window.open("", "_blank");
    void (async () => {
      try {
        const [{ phone }, body] = await Promise.all([whatsappApi.recipient(companyId), loadBody()]);
        if (!phone || !phone.trim()) {
          win?.close();
          toast(t("channel.noPhone"), "error");
          return;
        }
        const url = waMeUrl(phone, body ?? "");
        if (win) {
          win.location.href = url;
        } else {
          window.open(url, "_blank"); // popup was blocked — try a direct open
        }
      } catch {
        win?.close();
        toast(t("channel.whatsappOpenFailed"), "error");
      }
    })();
  }, [t, toast]);
}
