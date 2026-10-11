import { useQuery } from "@tanstack/react-query";
import { meApi } from "../api/me";

/**
 * The current tenant's WhatsApp mode, from the shared ["me"] bootstrap query. Drives the WhatsApp-send
 * behavior: {@code CLICK_TO_CHAT} opens a wa.me deep link (manual send) instead of the compose modal +
 * backend send used by {@code TWILIO}. Defaults to {@code OFF} until loaded.
 */
export function useWhatsAppMode(): "OFF" | "TWILIO" | "CLICK_TO_CHAT" {
  const { data } = useQuery({ queryKey: ["me"], queryFn: meApi.get, staleTime: 5 * 60_000 });
  return data?.whatsappMode ?? "OFF";
}
