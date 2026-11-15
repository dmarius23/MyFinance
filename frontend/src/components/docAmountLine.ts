/**
 * The "amount · date" subtitle under a document on the rep's home screen.
 *
 * This existed inline as `` `${money(total)} RON` ``, which appended a literal "RON" to every total —
 * so a EUR invoice was presented to the rep as RON, at roughly a fifth of its real value. That is the
 * single failure the currency work exists to prevent, and it was in the one place the client's own
 * representative looks, so the currency now travels with the amount.
 *
 * Kept as a standalone pure function so the wiring is unit-testable without mounting the whole portal.
 */
import { formatMoney } from "../lib/money";

/**
 * Composes the subtitle. Either part may be absent: an invoice that has not been extracted yet has no
 * total, and a document that is not an invoice has neither. Returns "" when there is nothing to show.
 *
 * `currency` falls back to RON only when the amount exists but its currency is unknown — the same
 * fallback the database default applies, so the label never reads "undefined".
 */
export function docAmountLine(total: number | null | undefined,
                              currency: string | null | undefined,
                              invoiceDate: string | null | undefined,
                              lang: string): string {
  return [total != null ? formatMoney(total, currency ?? "RON", lang) : null, invoiceDate || null]
    .filter(Boolean)
    .join(" · ");
}
