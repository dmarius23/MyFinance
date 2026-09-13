import { useQuery } from "@tanstack/react-query";
import { meApi } from "../api/me";

/**
 * Whether the current tenant may send email — true once the firm has configured & enabled its own SMTP
 * provider (Settings → Email provider). Email-send actions across the app are disabled until then.
 *
 * Reads the shared ["me"] bootstrap query (fetched by the app shell), so this adds no extra request.
 * Defaults to `true` while the query is still loading, so buttons aren't briefly flagged before we know —
 * once loaded, a non-configured tenant resolves to `false` and gates every send control.
 */
export function useEmailConfigured(): boolean {
  const { data } = useQuery({ queryKey: ["me"], queryFn: meApi.get, staleTime: 5 * 60_000 });
  return data ? data.emailConfigured : true;
}
