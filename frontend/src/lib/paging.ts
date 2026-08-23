import type { InfiniteData } from "@tanstack/react-query";

/** The minimal slice of a useInfiniteQuery result that {@link loadAllPages} needs. */
interface InfiniteLike<T> {
  data?: InfiniteData<{ content: T[] }>;
  hasNextPage: boolean;
  fetchNextPage: () => Promise<{ data?: InfiniteData<{ content: T[] }>; hasNextPage?: boolean }>;
}

/**
 * Fetch every remaining page of an infinite query and return the full, flattened list. Used by the
 * module "select all" actions so a bulk operation covers ALL companies matching the current filter —
 * not just the pages scrolled into view. Bounded by a hard page cap as a runaway guard.
 */
export async function loadAllPages<T>(query: InfiniteLike<T>): Promise<T[]> {
  let data = query.data;
  let hasNext = query.hasNextPage;
  let guard = 0;
  while (hasNext && guard++ < 1000) {
    const r = await query.fetchNextPage();
    data = r.data;
    hasNext = !!r.hasNextPage;
  }
  return data?.pages.flatMap((p) => p.content) ?? [];
}
