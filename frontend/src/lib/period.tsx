import { createContext, useContext, useMemo, useState, type ReactNode } from "react";

/** Current accounting month (yyyy-MM-01), shared across the shell topbar and the month-aware pages. */
function thisMonth(): string {
  return new Date().toISOString().slice(0, 7) + "-01";
}

function shift(period: string, delta: number): string {
  const d = new Date(period);
  const nd = new Date(d.getFullYear(), d.getMonth() + delta, 1);
  return `${nd.getFullYear()}-${String(nd.getMonth() + 1).padStart(2, "0")}-01`;
}

interface PeriodCtx {
  period: string;
  setPeriod: (p: string) => void;
  prev: () => void;
  next: () => void;
}

const Ctx = createContext<PeriodCtx | null>(null);

export function PeriodProvider({ children }: { children: ReactNode }) {
  const [period, setPeriod] = useState<string>(thisMonth);
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
