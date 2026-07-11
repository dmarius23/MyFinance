import { useCallback, useRef } from "react";
import { useSearchParams } from "react-router-dom";

/**
 * Deep-link focus for the module list pages. When a page is opened with `?company=<id>` — e.g. from the
 * dashboard status cells — the matching row is highlighted and scrolled into view. Returns the focused
 * company id and a ref callback to attach to that row.
 */
export function useCompanyFocus(): {
  focusCompany: string | null;
  focusRef: (el: HTMLDivElement | null) => void;
} {
  const [params] = useSearchParams();
  const focusCompany = params.get("company");
  const scrolled = useRef(false);
  const focusRef = useCallback((el: HTMLDivElement | null) => {
    if (el && !scrolled.current) {
      scrolled.current = true;
      el.scrollIntoView({ block: "center", behavior: "smooth" });
    }
  }, []);
  return { focusCompany, focusRef };
}
