import { createContext, useContext, useMemo, useState, type ReactNode } from "react";

/** Current calendar month (yyyy-MM-01). */
function thisMonth(): string {
  return new Date().toISOString().slice(0, 7) + "-01";
}

function shift(period: string, delta: number): string {
  const d = new Date(period);
  const nd = new Date(d.getFullYear(), d.getMonth() + delta, 1);
  return `${nd.getFullYear()}-${String(nd.getMonth() + 1).padStart(2, "0")}-01`;
}

/** Default accounting month = the PREVIOUS calendar month — accountants process last month's documents
 *  during the current one. So any day in August shows July, flipping to August on 1 September. */
function defaultPeriod(): string {
  return shift(thisMonth(), -1);
}

interface PeriodCtx {
  period: string;
  setPeriod: (p: string) => void;
  prev: () => void;
  next: () => void;
}

const Ctx = createContext<PeriodCtx | null>(null);

export function PeriodProvider({ children }: { children: ReactNode }) {
  const [period, setPeriod] = useState<string>(defaultPeriod);
  const value = useMemo<PeriodCtx>(() => ({
    period,
    setPeriod,
    prev: () => setPeriod((p) => shift(p, -1)),
    next: () => setPeriod((p) => shift(p, 1)),
  }), [period]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function usePeriod(): PeriodCtx {
  const c = useContext(Ctx);
  if (!c) throw new Error("usePeriod must be used within PeriodProvider");
  return c;
}

/** Human month label for a period (yyyy-MM-01), e.g. "mai 2026" / "May 2026". */
export function monthLabel(period: string, lang: string): string {
  return new Date(period).toLocaleDateString(lang === "ro" ? "ro-RO" : "en-US", { month: "long", year: "numeric" });
}
