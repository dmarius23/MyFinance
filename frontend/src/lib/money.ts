/**
 * One money formatter for the whole app. Amounts used to be formatted inline in at least eight
 * components, which is how an amount could be shown with no indication of its currency at all.
 *
 * The currency is ALWAYS printed, RON included. The first cut suppressed the "RON" suffix on the
 * theory that almost every client is RON-only and the suffix is noise. In practice that asymmetry
 * is worse than the noise: a bare number and a number whose currency was never extracted look
 * identical, so nobody can tell a working RON row from a broken one by looking at it. Accounting
 * statements name the currency on every line for exactly this reason.
 */
const DEFAULT_CURRENCY = "RON";

export function formatMoney(amount: number | string | null | undefined,
                            currency: string, lang: string): string {
  const n = typeof amount === "string" ? Number(amount) : amount;
  if (n === null || n === undefined || !Number.isFinite(n)) {
    return "—";
  }
  const locale = lang === "ro" ? "ro-RO" : "en-US";
  const text = n.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const ccy = (currency || DEFAULT_CURRENCY).toUpperCase();
  return `${text} ${ccy}`;
}
