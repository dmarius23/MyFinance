import type { FormEvent } from "react";

type FieldEl = HTMLInputElement | HTMLSelectElement;

/**
 * Spread onto a validated input so the constraint-validation bubble shows an EXPLICIT, field-named message
 * (e.g. "Invalid CUI — …") instead of the browser's generic default ("Please match the requested format").
 * onInvalid sets the message when the field fails on submit; onInput clears it so the field can re-validate
 * as the user types. Use alongside the field's own onChange.
 */
export function explicitValidity(message: string) {
  return {
    onInvalid: (e: FormEvent<FieldEl>) => e.currentTarget.setCustomValidity(message),
    onInput: (e: FormEvent<FieldEl>) => e.currentTarget.setCustomValidity(""),
  };
}
