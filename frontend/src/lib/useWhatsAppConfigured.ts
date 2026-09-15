import { useQuery } from "@tanstack/react-query";
import { meApi } from "../api/me";

/**
 * Whether the current tenant may send WhatsApp — true once the firm has a usable provider (Twilio with
 * credentials, or click-to-chat) in Settings → WhatsApp provider. WhatsApp-send actions are disabled until
 * then. Mirrors {@link useEmailConfigured}: reads the shared ["me"] bootstrap query (no extra request) and
 * defaults to `true` while loading so buttons aren't briefly mis-flagged.
 */
export function useWhatsAppConfigured(): boolean {
  const { data } = useQuery({ queryKey: ["me"], queryFn: meApi.get, staleTime: 5 * 60_000 });
  return data ? data.whatsappConfigured : true;
}
