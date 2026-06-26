/**
 * The company a representative is currently viewing in the PWA. A rep can be assigned to several
 * companies and switch between them; the chosen id is sent as the X-Company-Id header on every API
 * call. The backend always validates it against the rep's actual assignments, so this is only a hint.
 */
const KEY = "myfinance.activeCompanyId";

let current: string | null = (() => {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
})();

export function getActiveCompanyId(): string | null {
  return current;
}

export function setActiveCompanyId(id: string | null): void {
  current = id;
  try {
    if (id) localStorage.setItem(KEY, id);
    else localStorage.removeItem(KEY);
  } catch {
    /* ignore storage failures (private mode) */
  }
}
