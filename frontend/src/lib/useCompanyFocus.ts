import { useCallback, useRef } from "react";
import { useSearchParams } from "react-router-dom";

/**
 * Deep-link focus for the module list pages. When a page is opened with `?company=<id>` — e.g. from the
 * dashboard status cells — the matching row is highlighted and scrolled into view. With `&open=1` the
 * page should also open that company's modal. Returns the focused company id, a ref callback to attach to
 * that row, and whether the modal should auto-open.
 */
export function useCompanyFocus(): {
  focusCompany: string | null;
  focusRef: (el: HTMLDivElement | null) => void;
  openModal: boolean;
} {
  const [params] = useSearchParams();
  const focusCompany = params.get("company");
  const openModal = params.get("open") === "1";
  const scrolled = useRef(false);
  const focusRef = useCallback((el: HTMLDivElement | null) => {
    if (el && !scrolled.current) {
      scrolled.current = true;
      el.scrollIntoView({ block: "center", behavior: "smooth" });
    }
  }, []);
  return { focusCompany, focusRef, openModal };
}
