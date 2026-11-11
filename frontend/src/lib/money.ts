/**
 * One money formatter for the whole app. Amounts used to be formatted inline in at least eight
 * components, which is how an amount could be shown with no indication of its currency at all.
 *
 * The currency is printed ONLY when it is not RON: almost every client is RON-only, and suffixing every
 * amount with "RON" is noise — while a EUR amount shown bare is genuinely dangerous, because it reads as
 * RON and the two differ by roughly a factor of five.
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
  return ccy === DEFAULT_CURRENCY ? text : `${text} ${ccy}`;
}
