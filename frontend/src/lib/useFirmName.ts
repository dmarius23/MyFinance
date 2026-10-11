import { useQuery } from "@tanstack/react-query";
import { meApi } from "../api/me";

/**
 * The current tenant's own name (the accounting firm), used as the last line of every client-facing
 * message so the client sees which firm is writing. Never hardcode a firm name in a message template.
 *
 * Reads the shared ["me"] bootstrap query (fetched by the app shell), so this adds no extra request.
 * Returns `null` while loading or for a SUPER_ADMIN (no tenant); callers omit the line in that case
 * rather than rendering a placeholder.
 */
export function useFirmName(): string | null {
  const { data } = useQuery({ queryKey: ["me"], queryFn: meApi.get, staleTime: 5 * 60_000 });
  return data?.tenantName ?? null;
}
